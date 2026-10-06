package dev.lawlan.runline.engine.retention

import dev.lawlan.runline.engine.config.RetentionSettings
import java.time.Clock
import java.time.Instant
import java.util.EnumMap
import org.slf4j.LoggerFactory

/**
 * One pass of the retention clean-up (WI-20): removes the runs, logs and trigger firings whose
 * limit, counted back from the clock's now, has passed. Each kind is a step of its own, so one that
 * fails does not hold up the others, and a step removes its rows in batches of
 * [RetentionSettings.batchSize], each a short statement of its own, so the database is never held
 * for long. A step stops after [maxBatchesPerStep] batches, leaving the rest for the next pass;
 * that bounds a pass however large the backlog. Passes are safe to repeat: what is gone is not
 * looked for again.
 *
 * [clean] never throws: a failed step is counted, logged and reported, and the next pass tries
 * again. It is meant to be called by one scheduler (the Engine has a single instance).
 */
class RetentionCleaner(
    private val store: RetentionStore,
    private val settings: RetentionSettings,
    private val clock: Clock,
    private val telemetry: RetentionTelemetry,
    private val maxBatchesPerStep: Int = DEFAULT_MAX_BATCHES_PER_STEP,
) {
  private val log = LoggerFactory.getLogger(RetentionCleaner::class.java)

  private class Plan(val step: RetentionStep, val limit: Instant, val delete: Delete)

  private fun interface Delete {
    fun remove(limit: Instant, rows: Int): Int
  }

  private class Outcome(val removed: Int, val backlog: Boolean, val failed: Boolean)

  fun clean(): RetentionReport {
    val started = System.nanoTime()
    val now = clock.instant()
    // Logs come before runs: a run goes only when its log is gone.
    val plans =
        listOf(
            Plan(
                RetentionStep.LOG_ENTRIES,
                now - settings.logRetention,
                store::deleteExpiredLogEntries,
            ),
            Plan(RetentionStep.RUNS, now - settings.runRetention, store::deleteExpiredRuns),
            Plan(
                RetentionStep.WEBHOOK_FIRINGS,
                now - settings.webhookFiringRetention,
                store::deleteExpiredWebhookFirings,
            ),
            Plan(
                RetentionStep.CRON_FIRINGS,
                now - settings.cronFiringRetention,
                store::deleteExpiredCronFirings,
            ),
        )
    val removed = EnumMap<RetentionStep, Int>(RetentionStep::class.java)
    val failed = mutableListOf<RetentionStep>()
    var backlog = false
    for (plan in plans) {
      val outcome = run(plan)
      removed[plan.step] = outcome.removed
      backlog = backlog || outcome.backlog
      if (outcome.failed) failed += plan.step
    }
    val report =
        RetentionReport(
            runs = removed.getValue(RetentionStep.RUNS),
            logEntries = removed.getValue(RetentionStep.LOG_ENTRIES),
            webhookFirings = removed.getValue(RetentionStep.WEBHOOK_FIRINGS),
            cronFirings = removed.getValue(RetentionStep.CRON_FIRINGS),
            backlogRemains = backlog,
            failedSteps = failed,
        )
    val seconds = (System.nanoTime() - started) / 1e9
    telemetry.pass(seconds, report)
    logPass(report, seconds)
    return report
  }

  private fun run(plan: Plan): Outcome {
    var removed = 0
    try {
      repeat(maxBatchesPerStep) {
        val batch = plan.delete.remove(plan.limit, settings.batchSize)
        removed += batch
        telemetry.removed(plan.step, batch)
        if (batch < settings.batchSize) return Outcome(removed, backlog = false, failed = false)
      }
      return Outcome(removed, backlog = true, failed = false)
    } catch (e: Exception) {
      telemetry.failed(plan.step)
      log.error(
          "Retention clean-up step {} failed; it is tried again at the next pass",
          plan.step.label,
          e,
      )
      return Outcome(removed, backlog = false, failed = true)
    }
  }

  private fun logPass(report: RetentionReport, seconds: Double) {
    val millis = (seconds * 1000).toLong()
    val message =
        "Retention clean-up removed {} runs, {} log entries, {} webhook firings and {} cron firings in {} ms"
    val args =
        arrayOf<Any>(
            report.runs,
            report.logEntries,
            report.webhookFirings,
            report.cronFirings,
            millis,
        )
    val nothing = report.runs + report.logEntries + report.webhookFirings + report.cronFirings == 0
    if (nothing) log.debug(message, *args) else log.info(message, *args)
    if (report.backlogRemains) {
      log.warn("Retention clean-up reached its batch limit; more is left for the next pass")
    }
  }

  companion object {
    /** Batches one step may take in one pass; with the default batch size, 100000 rows. */
    const val DEFAULT_MAX_BATCHES_PER_STEP = 100
  }
}
