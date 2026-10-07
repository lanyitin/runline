package dev.lawlan.runline.engine.trigger

import ch.qos.logback.classic.Level
import dev.lawlan.runline.engine.fixtures.DefaultsPipeline
import dev.lawlan.runline.engine.run.RunSource
import dev.lawlan.runline.engine.run.RunState
import dev.lawlan.runline.engine.support.CapturedLogs
import dev.lawlan.runline.engine.support.RunHarness
import dev.lawlan.runline.engine.support.TriggerRig
import io.opentelemetry.api.common.AttributeKey
import java.time.Instant
import kotlin.test.*

class TriggerFirerTest {
  private val rig = TriggerRig()
  private val logs = CapturedLogs()
  private val h = rig.harness
  private val unsafeBody = "java.nio.file.Files.exists(java.nio.file.Path.of(\"x\"));"
  private var occurrence = Instant.parse("2026-10-05T00:00:00Z")

  @AfterTest
  fun close() {
    logs.close()
    rig.close()
  }

  /** Claims the next scheduled time of [trigger] and fires it. */
  private fun fire(trigger: Trigger): Pair<FiringOutcome, Firing> {
    occurrence = occurrence.plusSeconds(60)
    val id = checkNotNull(rig.store.claimOccurrence(trigger.id, occurrence, rig.clock.instant()))
    val outcome = rig.firer.fire(trigger, id)
    return outcome to rig.store.firings(trigger.id, 1).single()
  }

  @Test
  fun `a firing creates a run from the trigger with its parameters and the defaults of the version`() {
    val hash = h.uploadKotlin(DefaultsPipeline::class.java)
    val trigger = rig.cron("greeter", hash, "defaults", parameters = mapOf("env" to "prod"))

    val (outcome, firing) = fire(trigger)

    assertEquals(FiringOutcome.RUN_CREATED, outcome)
    assertEquals(FiringOutcome.RUN_CREATED, firing.outcome)
    val run = h.await(checkNotNull(firing.runId), RunState.SUCCEEDED)
    assertEquals(RunSource.Trigger("greeter"), run.source)
    assertEquals(mapOf("env" to "prod", "retries" to "3"), run.parameters)
    assertEquals(
        mapOf("kind=CRON,outcome=created,trigger=greeter" to 1L),
        rig.counter("runline.triggers.firings"),
    )
  }

  @Test
  fun `the trace of a run records that a trigger was its source`() {
    val hash = h.upload("calm")
    val trigger = rig.cron("nightly-cron", hash, "calm")

    val (_, firing) = fire(trigger)
    h.await(checkNotNull(firing.runId), RunState.SUCCEEDED)

    val root = rig.awaitSpan("runline.run")
    assertEquals("TRIGGER", root.attributes.get(AttributeKey.stringKey("runline.run.source.kind")))
    assertEquals(
        "nightly-cron",
        root.attributes.get(AttributeKey.stringKey("runline.run.source.name")),
    )
  }

  @Test
  fun `a refused creation leaves no run, is recorded with its reason and the trigger keeps firing`() {
    val hash = h.upload("risky", unsafeBody)
    val trigger = rig.cron("risky-cron", hash, "risky")

    val (outcome, firing) = fire(trigger)

    assertEquals(FiringOutcome.REFUSED, outcome)
    assertEquals(FiringOutcome.REFUSED, firing.outcome)
    assertEquals("unsafe_not_allowed", firing.reason)
    assertTrue(firing.detail!!.contains("risky"))
    assertNull(firing.runId)
    assertEquals(0, h.runCount())
    assertTrue(rig.store.find("risky-cron")!!.enabled)
    assertEquals(
        mapOf("kind=CRON,reason=unsafe_not_allowed,trigger=risky-cron" to 1L),
        rig.counter("runline.triggers.refused"),
    )
    assertTrue(
        logs.at(Level.WARN).any { it.contains("risky-cron") && it.contains("unsafe_not_allowed") },
        logs.lines.toString(),
    )

    // The administrator allows it; the next firing goes through.
    h.definitions.setUnsafeExecution(hash, "alice", "risky", true, "root", Instant.now())
    val (next, nextFiring) = fire(trigger)
    assertEquals(FiringOutcome.RUN_CREATED, next)
    h.await(checkNotNull(nextFiring.runId), RunState.SUCCEEDED)
  }

  @Test
  fun `a pipeline whose shared resource is not defined is refused until it is`() {
    val hash =
        h.upload(
            "needs-db",
            declaration = RunHarness.DEFAULT_DECLARATION + ", resources = {\"db\"}",
        )
    val trigger = rig.cron("db-cron", hash, "needs-db")

    val (outcome, firing) = fire(trigger)
    assertEquals(FiringOutcome.REFUSED, outcome)
    assertEquals("resources_unavailable", firing.reason)
    assertTrue(firing.detail!!.contains("db"))

    h.defineResource("db")
    assertEquals(FiringOutcome.RUN_CREATED, fire(trigger).first)
  }

  @Test
  fun `an unexpected failure is recorded as failed and the next firing tries again`() {
    val hash = h.upload("calm")
    val trigger = rig.cron("flaky", hash, "calm")
    // The database refuses new runs, as it would when something is wrong with it.
    execute("ALTER TABLE run ADD CONSTRAINT no_new_runs CHECK (false) NOT VALID")

    val (outcome, firing) = fire(trigger)

    assertEquals(FiringOutcome.FAILED, outcome)
    assertEquals(FiringOutcome.FAILED, firing.outcome)
    assertEquals("unexpected_error", firing.reason)
    assertNull(firing.runId)
    assertEquals(mapOf("kind=CRON,trigger=flaky" to 1L), rig.counter("runline.triggers.failed"))
    assertTrue(logs.at(Level.ERROR).any { it.contains("flaky") })

    execute("ALTER TABLE run DROP CONSTRAINT no_new_runs")
    assertEquals(FiringOutcome.RUN_CREATED, fire(trigger).first)
  }

  private fun execute(sql: String) =
      h.dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
}
