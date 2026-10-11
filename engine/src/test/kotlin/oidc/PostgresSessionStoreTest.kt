package dev.lawlan.runline.engine.oidc

import dev.lawlan.runline.engine.auth.Role
import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.support.PostgresTestContainer
import dev.lawlan.runline.engine.support.migratedDatabase
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** People and sessions on a real PostgreSQL (WI-1200, 06-data-model). */
class PostgresSessionStoreTest {
  private val database = migratedDatabase()
  private val store = PostgresSessionStore(dataSourceOf(database))
  private val now = Instant.parse("2026-10-10T10:00:00Z").truncatedTo(ChronoUnit.MICROS)
  private val idle = Duration.ofHours(8)
  private val absolute = Duration.ofDays(7)
  private val issuer = "https://auth.example.test/application/o/runline/"
  private val counter = AtomicInteger()

  private fun idHash(id: String) = MessageDigest.getInstance("SHA-256").digest(id.toByteArray())

  private fun session(
      id: String = "session-${counter.incrementAndGet()}",
      role: Role = Role.DEVELOPER,
      refresh: ByteArray = byteArrayOf(9),
      createdAt: Instant = now,
  ) = NewSession(idHash(id), role, "csrf-$id", refresh, createdAt, createdAt + absolute)

  private fun identity(subject: String, name: String, display: String = "Display $name") =
      SignInIdentity(issuer, subject, name, display)

  private fun count(table: String): Int =
      PostgresTestContainer.connect(database).use { c ->
        c.createStatement().use { s ->
          s.executeQuery("SELECT count(*) FROM $table").use { rs ->
            rs.next()
            rs.getInt(1)
          }
        }
      }

  private fun signedIn(result: SignInResult) = (result as SignInResult.SignedIn).user

  @Test
  fun `a first sign-in makes the person and the session, found by the hash of the identifier`() {
    val user =
        signedIn(
            store.signIn(
                identity("sub-1", "alice"),
                session("id-1", Role.ADMIN, byteArrayOf(1, 2, 3)),
                now,
            )
        )

    assertEquals("alice", user.name)
    assertEquals("Display alice", user.displayName)
    val found = assertNotNull(store.resume(idHash("id-1"), now, idle))
    assertEquals(user, found.user)
    assertEquals(Role.ADMIN, found.role)
    assertEquals("csrf-id-1", found.csrfToken)
    assertContentEquals(byteArrayOf(1, 2, 3), found.refreshToken)
    assertEquals(now, found.createdAt)
    assertEquals(now, found.lastValidatedAt)
    assertEquals(now + absolute, found.expiresAt)
    assertNull(store.resume(idHash("id-2"), now, idle))
  }

  @Test
  fun `the same person signing in again keeps the name, whatever they are called now`() {
    val first = signedIn(store.signIn(identity("sub-1", "alice", "Alice A"), session(), now))
    val later = now + Duration.ofDays(1)

    val second =
        signedIn(
            store.signIn(
                identity("sub-1", "alice-renamed", "Alice Anders"),
                session("again"),
                later,
            )
        )

    assertEquals(first.id, second.id)
    assertEquals("alice", second.name)
    assertEquals("Alice Anders", second.displayName)
    assertEquals(1, count("oidc_user"))
    assertEquals(2, count("user_session"))
  }

  @Test
  fun `the same subject at another provider is another person`() {
    store.signIn(identity("sub-1", "alice"), session(), now)

    val other =
        store.signIn(
            SignInIdentity("https://other.example.test/", "sub-1", "bob", "Bob"),
            session(),
            now,
        )

    assertEquals("bob", signedIn(other).name)
    assertEquals(2, count("oidc_user"))
  }

  @Test
  fun `a name another person has is refused and nothing at all is stored`() {
    store.signIn(identity("sub-1", "alice"), session("id-1"), now)

    val result = store.signIn(identity("sub-2", "alice"), session("id-2"), now)

    assertEquals(SignInResult.NameTaken, result)
    assertEquals(1, count("oidc_user"))
    assertEquals(1, count("user_session"))
    assertNull(store.resume(idHash("id-2"), now, idle))
  }

  @Test
  fun `a person who now has the name of another is still signed in under their own`() {
    store.signIn(identity("sub-1", "alice"), session(), now)
    store.signIn(identity("sub-2", "bob"), session(), now)

    // At the provider bob has taken the name alice had; his own name here stays bob.
    val result = store.signIn(identity("sub-2", "alice"), session("id-3"), now)

    assertEquals("bob", signedIn(result).name)
  }

  @Test
  fun `many sign-ins of one new person at the same time make one person`() {
    val callers = 8
    val pool = Executors.newFixedThreadPool(callers)
    try {
      val start = CountDownLatch(1)
      val results =
          (1..callers).map {
            pool.submit<SignInResult> {
              start.await()
              store.signIn(identity("sub-1", "alice"), session(), now)
            }
          }
      start.countDown()
      val users = results.map { signedIn(it.get(30, TimeUnit.SECONDS)) }

      assertEquals(1, users.map { it.id }.toSet().size)
      assertEquals(1, count("oidc_user"))
      assertEquals(callers, count("user_session"))
    } finally {
      pool.shutdownNow()
    }
  }

  @Test
  fun `of two new people with one name at the same time only one gets it`() {
    val callers = 6
    val pool = Executors.newFixedThreadPool(callers)
    try {
      val start = CountDownLatch(1)
      val results =
          (1..callers).map { n ->
            pool.submit<SignInResult> {
              start.await()
              store.signIn(identity("sub-$n", "alice"), session(), now)
            }
          }
      start.countDown()
      val outcomes = results.map { it.get(30, TimeUnit.SECONDS) }

      assertEquals(1, outcomes.count { it is SignInResult.SignedIn })
      assertEquals(callers - 1, outcomes.count { it == SignInResult.NameTaken })
      assertEquals(1, count("oidc_user"))
      assertEquals(1, count("user_session"))
    } finally {
      pool.shutdownNow()
    }
  }

  @Test
  fun `a session is valid until its expiry and not a moment after`() {
    store.signIn(identity("sub-1", "alice"), session("id-1"), now)

    assertNotNull(store.resume(idHash("id-1"), now + absolute - Duration.ofSeconds(1), absolute))
    assertNull(store.resume(idHash("id-1"), now + absolute, absolute))
  }

  @Test
  fun `a session unused for the idle time is invalid, and use renews the idle time`() {
    store.signIn(identity("sub-1", "alice"), session("id-1"), now)

    val used = now + idle - Duration.ofMinutes(1)
    assertNotNull(store.resume(idHash("id-1"), used, idle))
    // Counted from the last use, the session is still good after the first idle time has passed.
    val later = now + idle + Duration.ofMinutes(30)
    val found = assertNotNull(store.resume(idHash("id-1"), later, idle))
    assertEquals(later, found.lastUsedAt)
    // Left alone for the whole idle time it is gone, even though the absolute time is not over.
    assertNull(store.resume(idHash("id-1"), later + idle, idle))
    assertNull(store.resume(idHash("id-1"), later + Duration.ofMinutes(1), Duration.ofMinutes(1)))
  }

  @Test
  fun `a session that is not valid is not used up by being asked for`() {
    store.signIn(identity("sub-1", "alice"), session("id-1"), now)
    assertNull(store.resume(idHash("id-1"), now + absolute, idle))

    // It was not renewed by the failed asking: before its expiry it would still be found.
    assertNotNull(store.resume(idHash("id-1"), now + Duration.ofHours(1), idle))
  }

  @Test
  fun `ending a session removes it, and ending it again is no failure`() {
    store.signIn(identity("sub-1", "alice"), session("id-1"), now)

    assertTrue(store.end(idHash("id-1")))
    assertNull(store.resume(idHash("id-1"), now, idle))
    assertFalse(store.end(idHash("id-1")))
    assertFalse(store.end(idHash("never-was")))
    assertEquals(1, count("oidc_user"))
  }

  @Test
  fun `the database holds the hash of the identifier and not the identifier`() {
    store.signIn(identity("sub-1", "alice"), session("id-secret-value"), now)

    val stored =
        PostgresTestContainer.connect(database).use { c ->
          c.createStatement().use { s ->
            s.executeQuery("SELECT id_hash FROM user_session").use { rs ->
              rs.next()
              rs.getBytes(1)
            }
          }
        }
    assertContentEquals(idHash("id-secret-value"), stored)
    assertFalse(String(stored, Charsets.ISO_8859_1).contains("id-secret-value"))
  }

  @Test
  fun `a new person may not take a reserved name, a person who is known keeps theirs`() {
    val reserved = setOf("ci")

    val refused = store.signIn(identity("sub-1", "ci"), session("id-1"), now, reserved)

    assertEquals(SignInResult.NameTaken, refused)
    assertEquals(0, count("oidc_user"))
    assertEquals(0, count("user_session"))

    // Somebody who signed in before the name was reserved is still the same person.
    store.signIn(identity("sub-2", "dora"), session("id-2"), now)
    val again = store.signIn(identity("sub-2", "ci"), session("id-3"), now, reserved)
    assertEquals("dora", signedIn(again).name)
  }

  private fun lastUsed(id: String): Instant =
      PostgresTestContainer.connect(database).use { c ->
        c.prepareStatement("SELECT last_used_at FROM user_session WHERE id_hash = ?").use {
          it.setBytes(1, idHash(id))
          it.executeQuery().use { rs ->
            rs.next()
            rs.getObject(1, java.time.OffsetDateTime::class.java).toInstant()
          }
        }
      }

  @Test
  fun `use is written once in the time given, not by every request`() {
    val every = Duration.ofMinutes(1)
    store.signIn(identity("sub-1", "alice"), session("id-1"), now)

    repeat(5) { n ->
      assertNotNull(
          store.resume(idHash("id-1"), now + Duration.ofSeconds(10L * (n + 1)), idle, every)
      )
    }
    assertEquals(now, lastUsed("id-1"), "five requests within the minute wrote nothing")

    val later = now + Duration.ofSeconds(61)
    assertNotNull(store.resume(idHash("id-1"), later, idle, every))
    assertEquals(later, lastUsed("id-1"))
  }

  @Test
  fun `the idle time is still judged, from the last use that was written`() {
    val every = Duration.ofMinutes(1)
    store.signIn(identity("sub-1", "alice"), session("id-1"), now)
    store.resume(idHash("id-1"), now + Duration.ofSeconds(30), idle, every)

    assertNotNull(store.resume(idHash("id-1"), now + idle - Duration.ofSeconds(1), idle, every))
    // Nothing was written at 30 seconds, so the idle time counts from the start: the session that
    // was used at 8 hours less a second is renewed from there.
    val renewed = now + idle - Duration.ofSeconds(1)
    assertEquals(renewed, lastUsed("id-1"))
    assertNull(store.resume(idHash("id-1"), renewed + idle, idle, every))
  }
}
