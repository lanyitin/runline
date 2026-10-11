package dev.lawlan.runline.engine.oidc

import dev.lawlan.runline.engine.auth.Role
import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.support.PostgresTestContainer
import dev.lawlan.runline.engine.support.migratedDatabase
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/**
 * What administrators do with people and their sessions, on a real PostgreSQL (WI-1205): disabling
 * is one transaction with the removal of the sessions, and a sign-in at the same moment cannot
 * leave a session behind.
 */
class PostgresUserAdminStoreTest {
  private val database = migratedDatabase()
  private val dataSource = dataSourceOf(database)
  private val sessions = PostgresSessionStore(dataSource)
  private val admin = PostgresUserAdminStore(dataSource)
  private val now = Instant.parse("2026-10-10T10:00:00Z").truncatedTo(ChronoUnit.MICROS)
  private val idle = Duration.ofHours(8)
  private val counter = AtomicInteger()

  private fun hash(id: String) = SessionIds.hash(id)

  private fun signIn(name: String, id: String = "id-${counter.incrementAndGet()}"): SignInResult =
      sessions.signIn(
          SignInIdentity("https://auth.example.test/", "sub-$name", name, "Display $name"),
          NewSession(
              hash(id),
              Role.DEVELOPER,
              "csrf",
              byteArrayOf(1),
              now,
              now + Duration.ofDays(7),
          ),
          now,
      )

  private fun rows(table: String): Int =
      PostgresTestContainer.connect(database).use { c ->
        c.createStatement().use { s ->
          s.executeQuery("SELECT count(*) FROM $table").use {
            it.next()
            it.getInt(1)
          }
        }
      }

  @Test
  fun `people are listed with their state and the sessions that are valid`() {
    signIn("ann")
    signIn("ann")
    signIn("bob")
    // A session past its idle time is not counted.
    sessions.signIn(
        SignInIdentity("https://auth.example.test/", "sub-ann", "ann", "Display ann"),
        NewSession(
            hash("old"),
            Role.DEVELOPER,
            "c",
            byteArrayOf(1),
            now.minusSeconds(40000),
            now + Duration.ofDays(7),
        ),
        now.minusSeconds(40000),
    )

    val users = admin.list(now, idle).associateBy { it.name }

    assertEquals(setOf("ann", "bob"), users.keys)
    assertEquals(2, users.getValue("ann").sessions)
    assertEquals(1, users.getValue("bob").sessions)
    assertFalse(users.getValue("ann").disabled)
    assertNull(users.getValue("ann").statusChangedBy)
    assertEquals("Display ann", admin.find("ann", now, idle)!!.displayName)
    assertNull(admin.find("nobody", now, idle))
  }

  @Test
  fun `disabling ends every session of the person, records who and when, and touches nobody else`() {
    signIn("ann")
    signIn("ann")
    signIn("bob")
    val later = now + Duration.ofMinutes(5)

    val result = admin.setDisabled("ann", true, "root", later, idle)

    val changed = assertIs<DisableResult.Changed>(result)
    assertEquals(2, changed.sessionsEnded)
    assertTrue(changed.user.disabled)
    assertEquals("root", changed.user.statusChangedBy)
    assertEquals(later, changed.user.statusChangedAt)
    assertEquals(0, changed.user.sessions)
    assertEquals(1, rows("user_session"))
    assertEquals(1, admin.find("bob", now, idle)!!.sessions)
  }

  @Test
  fun `a disabled person cannot sign in, enabling lets them, and the state is recorded`() {
    signIn("ann")
    admin.setDisabled("ann", true, "root", now, idle)

    assertEquals(SignInResult.Disabled, signIn("ann"))
    assertEquals(0, rows("user_session"))

    val enabled = assertIs<DisableResult.Changed>(admin.setDisabled("ann", false, "ops", now, idle))
    assertFalse(enabled.user.disabled)
    assertEquals("ops", enabled.user.statusChangedBy)
    assertEquals(0, enabled.sessionsEnded)
    assertIs<SignInResult.SignedIn>(signIn("ann"))
  }

  @Test
  fun `a session of a disabled person is not valid even if a row were left`() {
    signIn("ann", "id-1")
    // The row is put back by hand, as a fault might: the question still says no.
    PostgresTestContainer.connect(database).use { c ->
      c.createStatement().use { it.execute("UPDATE oidc_user SET disabled = TRUE") }
    }

    assertNull(sessions.resume(hash("id-1"), now, idle))
  }

  @Test
  fun `a person who is not known is not found by any of the operations`() {
    assertEquals(DisableResult.NotFound, admin.setDisabled("ghost", true, "root", now, idle))
    assertNull(admin.sessions("ghost", now, idle))
    assertNull(admin.endAll("ghost"))
    assertFalse(admin.endSession("ghost", "00000000-0000-0000-0000-000000000000"))
  }

  @Test
  fun `sessions are listed by handle with times, and the handle names one to end`() {
    signIn("ann", "secret-id-1")
    signIn("ann", "secret-id-2")
    signIn("bob", "secret-id-3")

    val listed = admin.sessions("ann", now, idle)!!

    assertEquals(2, listed.size)
    for (info in listed) {
      assertEquals(now, info.createdAt)
      assertEquals(now, info.lastUsedAt)
      assertFalse(info.toString().contains("secret-id"))
      assertFalse(info.ref.contains("secret-id"))
    }
    assertTrue(admin.endSession("ann", listed[0].ref))
    assertFalse(admin.endSession("ann", listed[0].ref))
    assertEquals(1, admin.sessions("ann", now, idle)!!.size)
    // A handle of another person's session ends nothing of this person's.
    val bobs = admin.sessions("bob", now, idle)!!.single()
    assertFalse(admin.endSession("ann", bobs.ref))
    assertEquals(1, admin.sessions("bob", now, idle)!!.size)
  }

  @Test
  fun `ending all sessions of a person ends them and says how many`() {
    signIn("ann")
    signIn("ann")
    signIn("bob")

    assertEquals(2, admin.endAll("ann"))
    assertEquals(0, admin.endAll("ann"))
    assertEquals(1, rows("user_session"))
    assertEquals(2, rows("oidc_user"))
  }

  @Test
  fun `disabling at the same moment as sign-ins never leaves a session of the disabled person`() {
    repeat(15) { round ->
      val name = "racer-$round"
      signIn(name)
      val callers = 6
      val pool = Executors.newFixedThreadPool(callers + 1)
      try {
        val gate = CountDownLatch(1)
        val signIns =
            (1..callers).map {
              pool.submit<SignInResult> {
                gate.await()
                signIn(name)
              }
            }
        val disabling = pool.submit {
          gate.await()
          admin.setDisabled(name, true, "root", now, idle)
        }
        gate.countDown()
        val outcomes = signIns.map { it.get(60, TimeUnit.SECONDS) }
        disabling.get(60, TimeUnit.SECONDS)

        assertEquals(
            0,
            admin.find(name, now, idle)!!.sessions,
            "round $round left a session of a disabled person ($outcomes)",
        )
        assertTrue(admin.find(name, now, idle)!!.disabled)
      } finally {
        pool.shutdownNow()
      }
    }
  }
}
