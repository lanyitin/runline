package dev.lawlan.runline.engine.retention

import dev.lawlan.runline.engine.artifact.DeleteResult
import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.run.*
import dev.lawlan.runline.engine.support.RetentionData
import dev.lawlan.runline.engine.support.migratedDatabase
import dev.lawlan.runline.engine.trigger.*
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.*

/**
 * What is removed, and what is not, against a real PostgreSQL with the real migrations. Times are
 * given to the store, never waited for.
 */
class PostgresRetentionStoreTest {
  private val dataSource = dataSourceOf(migratedDatabase())
  private val now = Instant.parse("2026-10-05T12:00:00Z").truncatedTo(ChronoUnit.MICROS)
  private val data = RetentionData(dataSource, now)
  private val artifacts = data.artifacts
  private val definitions = data.definitions
  private val runs = data.runs
  private val triggers = data.triggers
  private val hash = data.hash
  private val store = PostgresRetentionStore(dataSource)
  private val day = Duration.ofDays(1)

  private val nonTerminal = RunState.entries.filter { !it.terminal }
  private val terminal = RunState.entries.filter { it.terminal }

  private fun run(state: RunState, endedAt: Instant = now - day, logLines: Int = 0) =
      data.run(state, endedAt, logLines)

  private fun exists(id: UUID) = data.exists(id)

  private fun logLines(id: UUID) = data.logLines(id)

  private fun count(table: String) = data.count(table)

  /** What the clean-up of runs does in one go: logs first, then the runs without logs. */
  private fun cleanRuns(runEnded: Instant, logEnded: Instant = runEnded, limit: Int = 1000) {
    while (store.deleteExpiredLogEntries(logEnded, limit) == limit) {}
    while (store.deleteExpiredRuns(runEnded, limit) == limit) {}
  }

  // ---- runs and their logs ----

  @Test
  fun `a run that ended before the limit is removed with all its log`() {
    val old = run(RunState.SUCCEEDED, endedAt = now - Duration.ofDays(31), logLines = 5)

    cleanRuns(runEnded = now - Duration.ofDays(30))

    assertFalse(exists(old))
    assertEquals(0, count("run_log_entry"))
  }

  @Test
  fun `a run that ended after the limit is kept with its log`() {
    val recent = run(RunState.SUCCEEDED, endedAt = now - Duration.ofDays(29), logLines = 5)

    cleanRuns(runEnded = now - Duration.ofDays(30))

    assertTrue(exists(recent))
    assertEquals(5, logLines(recent))
  }

  @Test
  fun `the limit is measured from when the run ended, not from when it was created`() {
    val limit = now - Duration.ofDays(30)
    // Created long ago, ended just now.
    val id = UUID.randomUUID()
    runs.insert(
        NewRun(
            id,
            data.definitionId,
            RunSource.Manual("alice"),
            emptyMap(),
            now - Duration.ofDays(90),
            null,
        )
    )
    runs.finish(id, RunState.FAILED, now - day)

    cleanRuns(runEnded = limit)

    assertTrue(exists(id))
  }

  @Test
  fun `a run that ended exactly at the limit is kept`() {
    val limit = now - Duration.ofDays(30)
    val id = run(RunState.FAILED, endedAt = limit)

    cleanRuns(runEnded = limit)

    assertTrue(exists(id))
  }

  @Test
  fun `every state in which a run has ended is removed`() {
    val ids = terminal.associateWith { run(it, endedAt = now - Duration.ofDays(60), logLines = 1) }

    cleanRuns(runEnded = now - Duration.ofDays(30))

    for ((state, id) in ids) assertFalse(exists(id), "$state was kept")
  }

  @Test
  fun `a run that has not ended is never removed however old it is`() {
    val ids = nonTerminal.associateWith {
      run(it, endedAt = now - Duration.ofDays(400), logLines = 2)
    }

    cleanRuns(runEnded = now, logEnded = now)

    for ((state, id) in ids) {
      assertTrue(exists(id), "$state was removed")
      assertEquals(2, logLines(id), "the log of $state was removed")
    }
  }

  @Test
  fun `the state decides, not the end time alone`() {
    // A run that has not ended should have no end time; if one is ever there, the state still wins.
    val ids = nonTerminal.associateWith { run(it, logLines = 1) }
    val withoutLog = nonTerminal.associateWith { run(it) }
    dataSource.connection.use { c ->
      c.createStatement().use { s ->
        s.executeUpdate("UPDATE run SET finished_at = '2020-01-01T00:00:00Z'")
      }
    }

    cleanRuns(runEnded = now, logEnded = now)

    for ((state, id) in ids) {
      assertTrue(exists(id), "$state was removed")
      assertEquals(1, logLines(id), "the log of $state was removed")
    }
    for ((state, id) in withoutLog) assertTrue(exists(id), "$state without a log was removed")
  }

  @Test
  fun `the log of a run that ended exactly at the log limit is kept`() {
    val limit = now - Duration.ofDays(14)
    val id = run(RunState.SUCCEEDED, endedAt = limit, logLines = 2)

    cleanRuns(runEnded = now - Duration.ofDays(30), logEnded = limit)

    assertEquals(2, logLines(id))
  }

  @Test
  fun `the log can be removed earlier than the run`() {
    val id = run(RunState.SUCCEEDED, endedAt = now - Duration.ofDays(20), logLines = 3)

    cleanRuns(runEnded = now - Duration.ofDays(30), logEnded = now - Duration.ofDays(14))

    assertTrue(exists(id), "the run record outlives its log")
    assertEquals(0, logLines(id))
  }

  @Test
  fun `the log of a run that has not ended is kept even when the log limit has passed`() {
    val id = run(RunState.RUNNING, endedAt = now - Duration.ofDays(20), logLines = 3)

    cleanRuns(runEnded = now - Duration.ofDays(30), logEnded = now)

    assertEquals(3, logLines(id))
  }

  @Test
  fun `a batch removes at most its limit and what is left goes in the next one`() {
    val old = now - Duration.ofDays(60)
    repeat(5) { run(RunState.SUCCEEDED, endedAt = old) }
    assertEquals(0, store.deleteExpiredLogEntries(now, 2))

    assertEquals(2, store.deleteExpiredRuns(now - Duration.ofDays(30), 2))
    assertEquals(2, store.deleteExpiredRuns(now - Duration.ofDays(30), 2))
    assertEquals(1, store.deleteExpiredRuns(now - Duration.ofDays(30), 2))
    assertEquals(0, store.deleteExpiredRuns(now - Duration.ofDays(30), 2))
    assertEquals(0, count("run"))
  }

  @Test
  fun `log entries go in batches of at most the limit, from a run with more lines than that`() {
    run(RunState.SUCCEEDED, endedAt = now - Duration.ofDays(60), logLines = 5)
    val limit = now - Duration.ofDays(30)

    assertEquals(2, store.deleteExpiredLogEntries(limit, 2))
    assertEquals(3, count("run_log_entry"))
    assertEquals(2, store.deleteExpiredLogEntries(limit, 2))
    assertEquals(1, store.deleteExpiredLogEntries(limit, 2))
    assertEquals(0, store.deleteExpiredLogEntries(limit, 2))
  }

  @Test
  fun `a batch of log entries is bounded across runs, not only within one run`() {
    repeat(3) { run(RunState.SUCCEEDED, endedAt = now - Duration.ofDays(60), logLines = 2) }
    val limit = now - Duration.ofDays(30)

    assertEquals(2, store.deleteExpiredLogEntries(limit, 2))
    assertEquals(4, count("run_log_entry"))
    assertEquals(2, store.deleteExpiredLogEntries(limit, 2))
    assertEquals(2, store.deleteExpiredLogEntries(limit, 2))
    assertEquals(0, store.deleteExpiredLogEntries(limit, 2))
  }

  @Test
  fun `runs without log past the limit do not hold up the runs behind them that have log`() {
    // The older runs have no log (it is gone, or they wrote none); a batch of two must not be
    // used up on them.
    repeat(5) { run(RunState.SUCCEEDED, endedAt = now - Duration.ofDays(60L - it)) }
    val withLog = run(RunState.SUCCEEDED, endedAt = now - Duration.ofDays(40), logLines = 2)

    assertEquals(2, store.deleteExpiredLogEntries(now - Duration.ofDays(30), 2))

    assertEquals(0, logLines(withLog))
  }

  @Test
  fun `a run whose log is not yet gone stays until its log is`() {
    val id = run(RunState.SUCCEEDED, endedAt = now - Duration.ofDays(60), logLines = 3)

    assertEquals(0, store.deleteExpiredRuns(now - Duration.ofDays(30), 10))
    assertTrue(exists(id))
  }

  @Test
  fun `running the clean-up again changes nothing`() {
    run(RunState.SUCCEEDED, endedAt = now - Duration.ofDays(60), logLines = 3)
    val keep = run(RunState.SUCCEEDED, endedAt = now - Duration.ofDays(1), logLines = 2)
    cleanRuns(runEnded = now - Duration.ofDays(30))

    assertEquals(0, store.deleteExpiredLogEntries(now - Duration.ofDays(30), 10))
    assertEquals(0, store.deleteExpiredRuns(now - Duration.ofDays(30), 10))

    assertEquals(listOf(keep), listOf(keep).filter(::exists))
    assertEquals(1, count("run"))
  }

  // ---- trigger firings ----

  private fun webhook() = data.webhook()

  private fun cron() = data.cron()

  private fun delivery(
      trigger: Trigger,
      id: String,
      at: Instant,
      outcome: FiringOutcome? = FiringOutcome.RUN_CREATED,
      run: UUID? = null,
  ) = data.delivery(trigger, id, at, outcome, run)

  private fun occurrence(
      trigger: Trigger,
      scheduledFor: Instant,
      at: Instant,
      outcome: FiringOutcome? = FiringOutcome.RUN_CREATED,
  ) = data.occurrence(trigger, scheduledFor, at, outcome)

  private fun firingIds(trigger: Trigger) = data.firingIds(trigger)

  @Test
  fun `webhook firings from before the limit are removed and later ones kept`() {
    val hook = webhook()
    val old = delivery(hook, "old", now - Duration.ofDays(8))
    val recent = delivery(hook, "recent", now - Duration.ofDays(6))
    val limit = now - Duration.ofDays(7)

    assertEquals(1, store.deleteExpiredWebhookFirings(limit, 10))

    assertEquals(setOf(recent), firingIds(hook))
    assertFalse(old in firingIds(hook))
  }

  @Test
  fun `a firing exactly at the limit is kept`() {
    val hook = webhook()
    val limit = now - Duration.ofDays(7)
    val at = delivery(hook, "edge", limit)

    assertEquals(0, store.deleteExpiredWebhookFirings(limit, 10))

    assertEquals(setOf(at), firingIds(hook))
  }

  @Test
  fun `a firing that is still pending is never removed`() {
    val hook = webhook()
    val cron = cron()
    delivery(hook, "stuck", now - Duration.ofDays(400), outcome = null)
    occurrence(cron, now - Duration.ofDays(400), now - Duration.ofDays(400), outcome = null)

    assertEquals(0, store.deleteExpiredWebhookFirings(now, 10))
    assertEquals(0, store.deleteExpiredCronFirings(now, 10))

    assertEquals(1, firingIds(hook).size)
    assertEquals(1, firingIds(cron).size)
  }

  @Test
  fun `every outcome other than pending is removed once it is expired`() {
    val hook = webhook()
    FiringOutcome.entries
        .filter { it != FiringOutcome.PENDING }
        .forEach { delivery(hook, "d-$it", now - Duration.ofDays(60), outcome = it) }

    assertEquals(4, store.deleteExpiredWebhookFirings(now - Duration.ofDays(7), 10))
  }

  @Test
  fun `webhook and cron firings have limits of their own`() {
    val hook = webhook()
    val cron = cron()
    val at = now - Duration.ofDays(20)
    delivery(hook, "d", at)
    occurrence(cron, at, at)

    // Seven days for webhooks does not touch a cron firing, and the other way round.
    assertEquals(1, store.deleteExpiredWebhookFirings(now - Duration.ofDays(7), 10))
    assertEquals(1, firingIds(cron).size)
    assertEquals(0, store.deleteExpiredCronFirings(now - Duration.ofDays(30), 10))
    assertEquals(1, store.deleteExpiredCronFirings(now - Duration.ofDays(7), 10))
    assertEquals(0, count("trigger_firing"))
  }

  @Test
  fun `firings go in batches of at most the limit`() {
    val hook = webhook()
    repeat(5) { delivery(hook, "d$it", now - Duration.ofDays(60)) }
    val limit = now - Duration.ofDays(7)

    assertEquals(2, store.deleteExpiredWebhookFirings(limit, 2))
    assertEquals(2, store.deleteExpiredWebhookFirings(limit, 2))
    assertEquals(1, store.deleteExpiredWebhookFirings(limit, 2))
    assertEquals(0, store.deleteExpiredWebhookFirings(limit, 2))
  }

  @Test
  fun `a delivery inside the window is a repeat and one after it is accepted again`() {
    val hook = webhook()
    val first = now - Duration.ofDays(6)
    delivery(hook, "same-id", first)

    assertNull(triggers.claimDelivery(hook.id, "same-id", now), "inside the window: a repeat")
    // Inside the window nothing is removed, so the repeat stays a repeat.
    assertEquals(0, store.deleteExpiredWebhookFirings(now - Duration.ofDays(7), 10))
    assertNull(triggers.claimDelivery(hook.id, "same-id", now))

    // Later the firing is older than the window and goes; the same identifier is new again.
    val later = now + Duration.ofDays(2)
    assertEquals(1, store.deleteExpiredWebhookFirings(later - Duration.ofDays(7), 10))
    assertNotNull(triggers.claimDelivery(hook.id, "same-id", later))
  }

  // ---- how runs and firings refer to one another ----

  @Test
  fun `removing a firing leaves the run it created`() {
    val hook = webhook()
    val created = run(RunState.SUCCEEDED, endedAt = now - Duration.ofDays(1), logLines = 2)
    delivery(hook, "d", now - Duration.ofDays(60), run = created)

    store.deleteExpiredWebhookFirings(now - Duration.ofDays(7), 10)

    assertTrue(exists(created))
    assertEquals(2, logLines(created))
  }

  @Test
  fun `removing a run keeps the firing that created it, without the run`() {
    val hook = webhook()
    val created = run(RunState.SUCCEEDED, endedAt = now - Duration.ofDays(60))
    val firing = delivery(hook, "d", now - Duration.ofDays(1), run = created)
    assertEquals(created, triggers.firings(hook.id, 10).single().runId)

    cleanRuns(runEnded = now - Duration.ofDays(30))

    assertFalse(exists(created))
    val kept = triggers.firings(hook.id, 10).single()
    assertEquals(firing, kept.id)
    assertEquals(FiringOutcome.RUN_CREATED, kept.outcome)
    assertNull(kept.runId)
  }

  // ---- what is never removed ----

  @Test
  fun `pipeline definitions and artifacts are never removed`() {
    run(RunState.SUCCEEDED, endedAt = now - Duration.ofDays(400), logLines = 1)
    cleanRuns(runEnded = now)

    assertNotNull(artifacts.find(hash, "alice"))
    assertNotNull(definitions.find(hash, "alice", "nightly"))
  }

  @Test
  fun `an artifact a run refers to cannot be deleted until the run is cleaned up`() {
    run(RunState.SUCCEEDED, endedAt = now - Duration.ofDays(60), logLines = 2)
    assertEquals(DeleteResult.InUse, artifacts.delete(hash, "alice"))

    cleanRuns(runEnded = now - Duration.ofDays(30))

    assertEquals(DeleteResult.Deleted, artifacts.delete(hash, "alice"))
  }

  @Test
  fun `an artifact a run has not finished with stays protected whatever the clean-up does`() {
    run(RunState.RUNNING, endedAt = now - Duration.ofDays(400))
    cleanRuns(runEnded = now, logEnded = now)

    assertEquals(DeleteResult.InUse, artifacts.delete(hash, "alice"))
  }

  @Test
  fun `an artifact a trigger is bound to stays protected after its firings and runs are cleaned`() {
    val hook = webhook()
    val created = run(RunState.SUCCEEDED, endedAt = now - Duration.ofDays(60))
    delivery(hook, "d", now - Duration.ofDays(60), run = created)

    cleanRuns(runEnded = now - Duration.ofDays(30))
    store.deleteExpiredWebhookFirings(now - Duration.ofDays(7), 10)

    assertEquals(0, count("run"))
    assertEquals(0, count("trigger_firing"))
    assertEquals(DeleteResult.InUse, artifacts.delete(hash, "alice"))
    assertNotNull(triggers.find("hook"), "the trigger itself is never removed")
  }
}
