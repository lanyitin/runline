package dev.lawlan.runline.engine.oidc

import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.support.migratedDatabase
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** The sign-ins under way, on a real PostgreSQL: short-lived and usable once. */
class PostgresLoginAttemptStoreTest {
  private val store = PostgresLoginAttemptStore(dataSourceOf(migratedDatabase()))
  private val now = Instant.parse("2026-10-10T10:00:00Z").truncatedTo(ChronoUnit.MICROS)

  private fun save(attempt: LoginAttempt) =
      store.save(attempt, Int.MAX_VALUE, now.minusSeconds(600))

  private fun attempt(state: String, at: Instant = now) =
      LoginAttempt(state, "nonce-$state", "verifier-$state", "/runs/1", at)

  @Test
  fun `a saved attempt is returned once with everything in it`() {
    save(attempt("s1"))

    assertEquals(attempt("s1"), store.consume("s1", now.minusSeconds(600)))
    assertNull(store.consume("s1", now.minusSeconds(600)))
  }

  @Test
  fun `a state nobody saved gives nothing`() {
    assertNull(store.consume("unknown", now.minusSeconds(600)))
  }

  @Test
  fun `an attempt older than the limit gives nothing, and is gone`() {
    save(attempt("old", now.minusSeconds(601)))

    assertNull(store.consume("old", now.minusSeconds(600)))
    // Even with a more generous limit it is not found again: asking removed it.
    assertNull(store.consume("old", now.minusSeconds(6000)))
  }

  @Test
  fun `an attempt exactly at the limit is still good`() {
    save(attempt("edge", now.minusSeconds(600)))

    assertNotNull(store.consume("edge", now.minusSeconds(600)))
  }

  @Test
  fun `of many calls at the same moment exactly one gets the attempt`() {
    save(attempt("race"))
    val callers = 8
    val pool = Executors.newFixedThreadPool(callers)
    try {
      val start = CountDownLatch(1)
      val results =
          (1..callers).map {
            pool.submit<LoginAttempt?> {
              start.await()
              store.consume("race", now.minusSeconds(600))
            }
          }
      start.countDown()

      assertEquals(1, results.count { it.get(30, TimeUnit.SECONDS) != null })
    } finally {
      pool.shutdownNow()
    }
  }

  @Test
  fun `the verifier is not in the text form of an attempt`() {
    assertFalse(attempt("s1").toString().contains("verifier-s1"))
  }

  // ---- the room for sign-ins under way (WI-1201, ADR-030) ----

  private val oldestGood = now.minusSeconds(600)

  @Test
  fun `up to the limit attempts are kept, then none, and nothing is written when refused`() {
    repeat(3) { n ->
      assertEquals(
          SaveResult.Saved,
          store.save(attempt("s$n", now.minusSeconds(10L * n)), 3, oldestGood),
      )
    }

    val refused = store.save(attempt("s3"), 3, oldestGood)

    assertEquals(SaveResult.Full(now.minusSeconds(20)), refused)
    assertNull(store.consume("s3", oldestGood))
    assertNotNull(store.consume("s0", oldestGood))
  }

  @Test
  fun `an attempt that was kept can still be used when the room is full, and its room is free after`() {
    store.save(attempt("a"), 2, oldestGood)
    store.save(attempt("b"), 2, oldestGood)
    assertIs<SaveResult.Full>(store.save(attempt("c"), 2, oldestGood))

    assertNotNull(store.consume("a", oldestGood))

    assertEquals(SaveResult.Saved, store.save(attempt("c"), 2, oldestGood))
  }

  @Test
  fun `attempts that are too old do not count, so the room returns as they expire`() {
    store.save(attempt("old1", now.minusSeconds(590)), 2, oldestGood)
    store.save(attempt("old2", now.minusSeconds(595)), 2, oldestGood)
    assertIs<SaveResult.Full>(store.save(attempt("new"), 2, oldestGood))

    // Ten seconds later neither of the two is still good, and nothing was cleaned up.
    val later = now.plusSeconds(10).minusSeconds(600)
    assertEquals(SaveResult.Saved, store.save(attempt("new", now.plusSeconds(10)), 2, later))
  }

  @Test
  fun `of many callers at the same moment no more than the limit are kept`() {
    val callers = 12
    val pool = Executors.newFixedThreadPool(callers)
    try {
      val start = CountDownLatch(1)
      val results =
          (1..callers).map { n ->
            pool.submit<SaveResult> {
              start.await()
              store.save(attempt("race-$n"), 5, oldestGood)
            }
          }
      start.countDown()
      val outcomes = results.map { it.get(30, TimeUnit.SECONDS) }

      assertEquals(5, outcomes.count { it == SaveResult.Saved })
      assertEquals(callers - 5, outcomes.count { it is SaveResult.Full })
    } finally {
      pool.shutdownNow()
    }
  }
}
