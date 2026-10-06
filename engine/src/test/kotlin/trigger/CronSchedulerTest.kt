package dev.lawlan.runline.engine.trigger

import ch.qos.logback.classic.Level
import dev.lawlan.runline.engine.run.RunSource
import dev.lawlan.runline.engine.run.RunState
import dev.lawlan.runline.engine.support.CapturedLogs
import dev.lawlan.runline.engine.support.MutableClock
import dev.lawlan.runline.engine.support.TriggerRig
import java.time.Duration
import java.time.Instant
import kotlin.test.*

/**
 * The scheduler is driven by a clock that only moves when a test moves it, and `tick` is called by
 * the test; nothing here waits for time to pass.
 */
class CronSchedulerTest {
  private val clock = MutableClock(Instant.parse("2026-10-05T10:00:00Z"))
  private val rig = TriggerRig(clock = clock)
  private val h = rig.harness
  private val logs = CapturedLogs()
  private val schedulers = mutableListOf<CronScheduler>()
  private val unsafeBody = "java.nio.file.Files.exists(java.nio.file.Path.of(\"x\"));"

  @AfterTest
  fun close() {
    schedulers.forEach { it.close() }
    logs.close()
    rig.close()
  }

  private fun scheduler() = CronScheduler(rig.store, rig.firer, clock).also { schedulers += it }

  private fun at(text: String) =
      clock.advance(Duration.between(clock.instant(), Instant.parse(text)))

  private fun firings(trigger: Trigger) = rig.store.firings(trigger.id, 1000).reversed()

  private fun scheduledTimes(trigger: Trigger) = firings(trigger).map { it.scheduledFor.toString() }

  // ---- when a trigger fires ----

  @Test
  fun `a cron trigger creates a run when its time comes and not before`() {
    val trigger = rig.cron("every-five", h.upload("calm"), "calm", "*/5 * * * *")
    val scheduler = scheduler()

    at("2026-10-05T10:04:59Z")
    scheduler.tick()
    assertEquals(0, h.runCount())

    at("2026-10-05T10:05:00Z")
    scheduler.tick()
    assertEquals(1, h.runCount())
    val firing = firings(trigger).single()
    assertEquals(Instant.parse("2026-10-05T10:05:00Z"), firing.scheduledFor)
    assertEquals(FiringOutcome.RUN_CREATED, firing.outcome)
    val run = h.await(checkNotNull(firing.runId), RunState.SUCCEEDED)
    assertEquals(RunSource.Trigger("every-five"), run.source)
  }

  @Test
  fun `the same time does not create a run twice`() {
    val trigger = rig.cron("every-five", h.upload("calm"), "calm", "*/5 * * * *")
    val first = scheduler()
    val second = scheduler()
    at("2026-10-05T10:05:00Z")

    first.tick()
    first.tick()
    clock.advance(Duration.ofSeconds(30))
    first.tick()
    // Even a second scheduler looking at the same time cannot fire it again.
    second.tick()

    assertEquals(1, h.runCount())
    assertEquals(1, firings(trigger).size)
  }

  @Test
  fun `each scheduled time fires once as time moves on`() {
    val trigger = rig.cron("every-five", h.upload("calm"), "calm", "*/5 * * * *")
    val scheduler = scheduler()

    // Step through half an hour a minute at a time, as the background loop would.
    repeat(31) {
      scheduler.tick()
      clock.advance(Duration.ofMinutes(1))
    }

    assertEquals(
        listOf(
            "2026-10-05T10:05:00Z",
            "2026-10-05T10:10:00Z",
            "2026-10-05T10:15:00Z",
            "2026-10-05T10:20:00Z",
            "2026-10-05T10:25:00Z",
            "2026-10-05T10:30:00Z",
        ),
        scheduledTimes(trigger),
    )
  }

  @Test
  fun `a disabled trigger does not fire and fires from the next time once it is enabled`() {
    val trigger = rig.cron("every-five", h.upload("calm"), "calm", "*/5 * * * *", enabled = false)
    val scheduler = scheduler()
    at("2026-10-05T10:05:00Z")
    scheduler.tick()
    assertEquals(0, h.runCount())

    // Enabled at 10:07, it does not make up 10:05 and fires at 10:10.
    at("2026-10-05T10:07:00Z")
    rig.admin.update("every-five", UpdateTrigger(enabled = true), rig.root)
    scheduler.tick()
    assertEquals(0, h.runCount())
    at("2026-10-05T10:10:00Z")
    scheduler.tick()

    assertEquals(listOf("2026-10-05T10:10:00Z"), scheduledTimes(trigger))
  }

  @Test
  fun `a trigger that is disabled or deleted stops firing`() {
    val trigger = rig.cron("a", h.upload("calm"), "calm", "*/5 * * * *")
    rig.cron("b", h.upload("other"), "other", "*/5 * * * *")
    val scheduler = scheduler()
    at("2026-10-05T10:05:00Z")
    scheduler.tick()
    assertEquals(2, h.runCount())

    rig.admin.update("a", UpdateTrigger(enabled = false), rig.root)
    rig.admin.delete("b", rig.root)
    at("2026-10-05T10:10:00Z")
    scheduler.tick()

    assertEquals(2, h.runCount())
    assertEquals(1, firings(trigger).size)
  }

  @Test
  fun `a change to the schedule applies from the change on`() {
    val trigger = rig.cron("t", h.upload("calm"), "calm", "0 12 * * *")
    val scheduler = scheduler()
    at("2026-10-05T10:30:00Z")

    rig.admin.update("t", UpdateTrigger(cronExpression = "*/10 * * * *"), rig.root)
    scheduler.tick()
    assertEquals(0, h.runCount())
    at("2026-10-05T10:40:00Z")
    scheduler.tick()

    assertEquals(listOf("2026-10-05T10:40:00Z"), scheduledTimes(trigger))
  }

  // ---- restart and missed times ----

  @Test
  fun `after a restart the enabled trigger fires again and what was missed is not made up`() {
    val trigger = rig.cron("every-five", h.upload("calm"), "calm", "*/5 * * * *")
    val before = scheduler()
    at("2026-10-05T10:05:00Z")
    before.tick()
    assertEquals(1, h.runCount())
    before.close()

    // The Engine is down for two hours; 10:10 to 12:00 are missed.
    at("2026-10-05T12:03:20Z")
    val after = scheduler()
    after.tick()
    assertEquals(1, h.runCount(), "nothing is made up on start")

    at("2026-10-05T12:05:00Z")
    after.tick()

    assertEquals(
        listOf("2026-10-05T10:05:00Z", "2026-10-05T12:05:00Z"),
        scheduledTimes(trigger),
    )
  }

  @Test
  fun `a daily time that passed while the process was stalled is not made up`() {
    val trigger = rig.cron("daily", h.upload("calm"), "calm", "0 11 * * *")
    val scheduler = scheduler()

    // The clock jumps from 10:00 to 13:00 with no tick in between.
    at("2026-10-05T13:00:00Z")
    scheduler.tick()

    assertEquals(0, h.runCount())
    assertTrue(firings(trigger).isEmpty())
    at("2026-10-06T11:00:00Z")
    scheduler.tick()
    assertEquals(listOf("2026-10-06T11:00:00Z"), scheduledTimes(trigger))
  }

  @Test
  fun `several times missed within a stall fire the latest one only`() {
    val trigger = rig.cron("every-minute", h.upload("calm"), "calm", "* * * * *")
    val scheduler = scheduler()

    at("2026-10-05T10:07:30Z")
    scheduler.tick()

    assertEquals(listOf("2026-10-05T10:07:00Z"), scheduledTimes(trigger))
  }

  // ---- time zones and daylight saving time ----

  @Test
  fun `the expression is read in the trigger's time zone`() {
    val trigger = rig.cron("morning", h.upload("calm"), "calm", "0 9 * * *", zone = "Asia/Taipei")
    val scheduler = scheduler()

    at("2026-10-05T09:00:00Z")
    scheduler.tick()
    assertTrue(firings(trigger).isEmpty(), "09:00 UTC is 17:00 in Taipei")
    at("2026-10-06T01:00:00Z")
    scheduler.tick()

    assertEquals(listOf("2026-10-06T01:00:00Z"), scheduledTimes(trigger))
  }

  @Test
  fun `a time that does not exist when clocks go forward fires once, after the gap`() {
    clock.advance(Duration.between(clock.instant(), Instant.parse("2026-03-08T05:00:00Z")))
    val trigger = rig.cron("ny", h.upload("calm"), "calm", "30 2 * * *", zone = "America/New_York")
    val scheduler = scheduler()

    stepUntil("2026-03-10T08:00:00Z", scheduler)

    // 02:30 does not exist on 8 March in New York; it fires at 03:30 EDT. The days around it are
    // 02:30 EST (07:30Z) on the 9th and 02:30 EDT (06:30Z) on the 10th.
    assertEquals(
        listOf("2026-03-08T07:30:00Z", "2026-03-09T06:30:00Z", "2026-03-10T06:30:00Z"),
        scheduledTimes(trigger),
    )
  }

  @Test
  fun `a time that happens twice when clocks go back fires once`() {
    clock.advance(Duration.between(clock.instant(), Instant.parse("2026-11-01T03:00:00Z")))
    val trigger = rig.cron("ny", h.upload("calm"), "calm", "30 1 * * *", zone = "America/New_York")
    val scheduler = scheduler()

    stepUntil("2026-11-01T09:00:00Z", scheduler)

    // 01:30 happens as 01:30 EDT (05:30Z) and again as 01:30 EST (06:30Z): once.
    assertEquals(listOf("2026-11-01T05:30:00Z"), scheduledTimes(trigger))
  }

  // ---- refusals and errors ----

  @Test
  fun `a refused creation is recorded, the trigger stays enabled and tries again at the next time`() {
    val trigger = rig.cron("risky", h.upload("risky", unsafeBody), "risky", "*/5 * * * *")
    val scheduler = scheduler()

    at("2026-10-05T10:05:00Z")
    scheduler.tick()
    at("2026-10-05T10:10:00Z")
    scheduler.tick()

    assertEquals(0, h.runCount())
    assertEquals(
        listOf(FiringOutcome.REFUSED, FiringOutcome.REFUSED),
        firings(trigger).map { it.outcome },
    )
    assertTrue(rig.store.find("risky")!!.enabled)
    assertEquals(2L, rig.counter("runline.triggers.refused").values.single())
  }

  @Test
  fun `a failure while firing is logged and the same time is tried again on the next tick`() {
    val trigger = rig.cron("t", h.upload("calm"), "calm", "*/5 * * * *")
    val scheduler = scheduler()
    at("2026-10-05T10:05:00Z")
    // The database refuses to record a firing, as it would when something is wrong with it.
    execute("ALTER TABLE trigger_firing ADD CONSTRAINT no_firings CHECK (false) NOT VALID")

    scheduler.tick()

    assertEquals(0, h.runCount())
    assertTrue(logs.at(Level.ERROR).isNotEmpty())

    execute("ALTER TABLE trigger_firing DROP CONSTRAINT no_firings")
    clock.advance(Duration.ofSeconds(1))
    scheduler.tick()

    assertEquals(listOf("2026-10-05T10:05:00Z"), scheduledTimes(trigger))
    assertEquals(1, h.runCount())
  }

  @Test
  fun `a scheduler that was started can be closed`() {
    val scheduler = scheduler()
    scheduler.start()
    scheduler.close()
  }

  /** Ticks once a minute from the current time up to [end]. */
  private fun stepUntil(end: String, scheduler: CronScheduler) {
    val stop = Instant.parse(end)
    while (clock.instant().isBefore(stop)) {
      scheduler.tick()
      clock.advance(Duration.ofMinutes(1))
    }
  }

  private fun execute(sql: String) =
      h.dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
}
