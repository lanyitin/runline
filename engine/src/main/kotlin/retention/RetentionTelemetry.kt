package dev.lawlan.runline.engine.retention

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes

/**
 * Metrics of the retention clean-up (07-nfr-risks): what it removed (runs, log entries, trigger
 * firings by kind), the steps that failed, and passes by result with how long each took.
 */
class RetentionTelemetry(openTelemetry: OpenTelemetry) {
  private val meter = openTelemetry.getMeter("runline.retention")
  private val runs = meter.counterBuilder("runline.retention.runs.removed").build()
  private val logEntries = meter.counterBuilder("runline.retention.log_entries.removed").build()
  private val firings = meter.counterBuilder("runline.retention.trigger_firings.removed").build()
  private val failures = meter.counterBuilder("runline.retention.failures").build()
  private val passes = meter.counterBuilder("runline.retention.passes").build()
  private val duration = meter.histogramBuilder("runline.retention.duration").setUnit("s").build()

  fun removed(step: RetentionStep, count: Int) {
    if (count == 0) return
    val n = count.toLong()
    when (step) {
      RetentionStep.RUNS -> runs.add(n)
      RetentionStep.LOG_ENTRIES -> logEntries.add(n)
      RetentionStep.WEBHOOK_FIRINGS -> firings.add(n, Attributes.of(KIND, "webhook"))
      RetentionStep.CRON_FIRINGS -> firings.add(n, Attributes.of(KIND, "cron"))
    }
  }

  fun failed(step: RetentionStep) {
    failures.add(1, Attributes.of(STEP, step.label))
  }

  fun pass(seconds: Double, report: RetentionReport) {
    passes.add(1, Attributes.of(RESULT, if (report.succeeded) "completed" else "failed"))
    duration.record(seconds)
  }

  private companion object {
    val KIND = AttributeKey.stringKey("kind")
    val STEP = AttributeKey.stringKey("step")
    val RESULT = AttributeKey.stringKey("result")
  }
}
