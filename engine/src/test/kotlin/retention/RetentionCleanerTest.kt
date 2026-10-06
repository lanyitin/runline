package dev.lawlan.runline.engine.retention

import ch.qos.logback.classic.Level
import dev.lawlan.runline.engine.config.RetentionSettings
import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.run.RunState
import dev.lawlan.runline.engine.support.CapturedLogs
import dev.lawlan.runline.engine.support.MutableClock
import dev.lawlan.runline.engine.support.RetentionData
import dev.lawlan.runline.engine.support.migratedDatabase
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import java.time.Duration
import kotlin.test.*

/**
 * One pass of the clean-up against a real PostgreSQL: which rows go by the clock, in how many
 * batches, what is reported, and what happens when the database fails.
 */
class RetentionCleanerTest {
  private val database = migratedDatabase()
  private val dataSource = dataSourceOf(database)
  private val clock = MutableClock()
  private val data = RetentionData(dataSource, clock.instant())
  private val metrics: InMemoryMetricReader = InMemoryMetricReader.create()
  private val telemetry =
      RetentionTelemetry(
          OpenTelemetrySdk.builder()
              .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metrics).build())
              .build()
      )
  private val logs = CapturedLogs()

  private val settings =
      RetentionSettings(
          runRetention = Duration.ofDays(30),
          logRetention = Duration.ofDays(14),
          webhookFiringRetention = Duration.ofDays(7),
          cronFiringRetention = Duration.ofDays(30),
          interval = Duration.ofHours(1),
          batchSize = 1000,
      )

  private fun cleaner(
      settings: RetentionSettings = this.settings,
      maxBatchesPerStep: Int = 100,
  ) =
      RetentionCleaner(
          PostgresRetentionStore(dataSource),
          settings,
          clock,
          telemetry,
          maxBatchesPerStep,
      )

  private fun ago(days: Long) = clock.instant() - Duration.ofDays(days)

  @AfterTest fun close() = logs.close()

  /** The counter [name] as attribute text to value, for example `kind=webhook`. */
  private fun counter(name: String): Map<String, Long> =
      metrics
          .collectAllMetrics()
          .firstOrNull { it.name == name }
          ?.longSumData
          ?.points
          .orEmpty()
          .associate {
            it.attributes
                .asMap()
                .entries
                .sortedBy { e -> e.key.key }
                .joinToString(",") { e -> "${e.key.key}=${e.value}" } to it.value
          }

  // ---- what goes, by the clock ----

  @Test
  fun `a pass removes what is past its limit and keeps the rest`() {
    val oldRun = data.run(RunState.SUCCEEDED, ago(31), logLines = 3)
    val middleRun = data.run(RunState.FAILED, ago(20), logLines = 4)
    val freshRun = data.run(RunState.SUCCEEDED, ago(1), logLines = 2)
    val running = data.run(RunState.RUNNING, ago(100), logLines = 1)
    val hook = data.webhook()
    val tick = data.cron()
    val oldDelivery = data.delivery(hook, "old", ago(8))
    val freshDelivery = data.delivery(hook, "fresh", ago(6))
    val pendingDelivery = data.delivery(hook, "stuck", ago(100), outcome = null)
    val oldTick = data.occurrence(tick, ago(31), ago(31))
    val freshTick = data.occurrence(tick, ago(29), ago(29))

    val report = cleaner().clean()

    assertTrue(report.succeeded)
    assertFalse(data.exists(oldRun))
    assertTrue(data.exists(middleRun), "the run record is kept for 30 days")
    assertEquals(0, data.logLines(middleRun), "its log only for 14")
    assertEquals(2, data.logLines(freshRun))
    assertTrue(data.exists(running))
    assertEquals(1, data.logLines(running))
    assertEquals(setOf(freshDelivery, pendingDelivery), data.firingIds(hook))
    assertFalse(oldDelivery in data.firingIds(hook))
    assertEquals(setOf(freshTick), data.firingIds(tick))
    assertFalse(oldTick in data.firingIds(tick))
  }

  @Test
  fun `the report and the metrics say how much was removed`() {
    data.run(RunState.SUCCEEDED, ago(31), logLines = 3)
    data.run(RunState.FAILED, ago(40), logLines = 2)
    data.run(RunState.FAILED, ago(20), logLines = 4)
    val hook = data.webhook()
    val tick = data.cron()
    repeat(3) { data.delivery(hook, "d$it", ago(8)) }
    repeat(2) { data.occurrence(tick, ago(40 + it.toLong()), ago(40 + it.toLong())) }

    val report = cleaner().clean()

    assertEquals(
        RetentionReport(
            runs = 2,
            // The old runs' 5 lines, and the middle run's 4: the log limit is shorter.
            logEntries = 9,
            webhookFirings = 3,
            cronFirings = 2,
        ),
        report,
    )
    assertEquals(mapOf("" to 2L), counter("runline.retention.runs.removed"))
    assertEquals(mapOf("" to 9L), counter("runline.retention.log_entries.removed"))
    assertEquals(
        mapOf("kind=webhook" to 3L, "kind=cron" to 2L),
        counter("runline.retention.trigger_firings.removed"),
    )
    assertEquals(mapOf("result=completed" to 1L), counter("runline.retention.passes"))
    val duration = metrics.collectAllMetrics().single { it.name == "runline.retention.duration" }
    assertEquals(1L, duration.histogramData.points.single().count)
  }

  @Test
  fun `as the clock moves on more of it expires`() {
    val hook = data.webhook()
    val kept = data.delivery(hook, "d", ago(6))
    val run = data.run(RunState.SUCCEEDED, ago(29))

    assertEquals(RetentionReport(), cleaner().clean())
    assertEquals(setOf(kept), data.firingIds(hook))

    clock.advance(Duration.ofDays(2))
    val report = cleaner().clean()

    assertEquals(1, report.webhookFirings)
    assertEquals(1, report.runs)
    assertFalse(data.exists(run))
    assertEquals(emptySet(), data.firingIds(hook))
  }

  @Test
  fun `a second pass has nothing left to do`() {
    data.run(RunState.SUCCEEDED, ago(40), logLines = 3)
    val hook = data.webhook()
    data.delivery(hook, "d", ago(40))
    assertNotEquals(RetentionReport(), cleaner().clean())

    assertEquals(RetentionReport(), cleaner().clean())
  }

  // ---- bounded work ----

  @Test
  fun `a backlog larger than a batch is cleared in batches within one pass`() {
    repeat(5) { data.run(RunState.SUCCEEDED, ago(40), logLines = 5) }
    val hook = data.webhook()
    repeat(5) { data.delivery(hook, "d$it", ago(40)) }

    val report = cleaner(settings.copy(batchSize = 2)).clean()

    assertEquals(
        RetentionReport(runs = 5, logEntries = 25, webhookFirings = 5),
        report,
    )
    assertEquals(0, data.count("run"))
  }

  @Test
  fun `a pass stops at its batch limit and the next one goes on`() {
    val hook = data.webhook()
    repeat(5) { data.delivery(hook, "d$it", ago(40)) }
    val cleaner = cleaner(settings.copy(batchSize = 1), maxBatchesPerStep = 2)

    val first = cleaner.clean()
    assertEquals(2, first.webhookFirings)
    assertTrue(first.backlogRemains)
    assertEquals(3, data.firingIds(hook).size)

    assertEquals(2, cleaner.clean().webhookFirings)
    val last = cleaner.clean()
    assertEquals(1, last.webhookFirings)
    assertFalse(last.backlogRemains)
    assertEquals(0, data.firingIds(hook).size)
  }

  @Test
  fun `a run with more log lines than the limit of a pass is removed over several passes`() {
    val run = data.run(RunState.SUCCEEDED, ago(40), logLines = 7)
    val cleaner = cleaner(settings.copy(batchSize = 2), maxBatchesPerStep = 2)

    assertTrue(cleaner.clean().backlogRemains)
    assertTrue(data.exists(run), "the run stays until its log is gone")
    assertTrue(cleaner.clean().backlogRemains.not())

    assertFalse(data.exists(run))
    assertEquals(0, data.count("run_log_entry"))
  }

  // ---- logging ----

  @Test
  fun `a pass that removed something is logged with the counts`() {
    data.run(RunState.SUCCEEDED, ago(40), logLines = 3)

    cleaner().clean()

    val line = logs.at(Level.INFO).single { it.contains("Retention clean-up") }
    assertTrue("1 runs" in line && "3 log entries" in line, line)
  }

  @Test
  fun `a pass that stops at its batch limit says so`() {
    val hook = data.webhook()
    repeat(3) { data.delivery(hook, "d$it", ago(40)) }

    cleaner(settings.copy(batchSize = 1), maxBatchesPerStep = 1).clean()

    assertTrue(logs.at(Level.WARN).any { it.contains("more") }, "${logs.lines}")
  }

  // ---- failures ----

  private fun execute(sql: String) =
      dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }

  @Test
  fun `a failing step is reported and logged, the other steps are done, and the next pass retries`() {
    val oldRun = data.run(RunState.SUCCEEDED, ago(40), logLines = 2)
    val hook = data.webhook()
    data.delivery(hook, "d", ago(40))
    execute("ALTER TABLE run RENAME TO run_away")

    val failed = cleaner().clean()

    assertFalse(failed.succeeded)
    assertEquals(listOf(RetentionStep.LOG_ENTRIES, RetentionStep.RUNS), failed.failedSteps)
    assertEquals(1, failed.webhookFirings, "the firings do not wait for the runs")
    assertEquals(
        mapOf("step=log_entries" to 1L, "step=runs" to 1L),
        counter("runline.retention.failures"),
    )
    assertEquals(mapOf("result=failed" to 1L), counter("runline.retention.passes"))
    assertTrue(
        logs.at(Level.ERROR).any { it.contains("log_entries") },
        "the failure is logged: ${logs.lines}",
    )

    execute("ALTER TABLE run_away RENAME TO run")
    val retried = cleaner().clean()

    assertTrue(retried.succeeded)
    assertEquals(1, retried.runs)
    assertFalse(data.exists(oldRun))
  }

  @Test
  fun `a failure does not put the database password in the log`() {
    data.run(RunState.SUCCEEDED, ago(40))
    execute("ALTER TABLE run RENAME TO run_away")

    cleaner().clean()

    val everything = logs.lines.joinToString("\n")
    assertFalse(database.password in everything, "the database password is in the log")
    assertTrue(everything.contains("Retention clean-up"), everything)
  }
}
