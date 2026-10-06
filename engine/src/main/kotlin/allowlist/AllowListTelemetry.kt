package dev.lawlan.runline.engine.allowlist

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.StatusCode

/**
 * Trace and metrics of allow list changes (WI-10, 07-nfr-risks). Every operation, including a
 * preview, runs inside a span carrying what it was and the name of who asked (never a token); the
 * counters tell how many operations there were and how many verdicts the changes flipped, in each
 * direction.
 */
class AllowListTelemetry(openTelemetry: OpenTelemetry) {
  private val tracer = openTelemetry.getTracer("runline.allowlist")
  private val meter = openTelemetry.getMeter("runline.allowlist")
  private val operations = meter.counterBuilder("runline.allowlist.operations").build()
  private val verdictChanges = meter.counterBuilder("runline.allowlist.verdict.changes").build()

  /** Runs [block] in a span and counts the operation. */
  fun <T> observe(operation: String, preview: Boolean, by: String, block: () -> T): T {
    val span =
        tracer
            .spanBuilder("runline.allowlist.change")
            .setAttribute(OPERATION, operation)
            .setAttribute(PREVIEW, preview)
            .setAttribute(ACTOR, by)
            .startSpan()
    try {
      return span.makeCurrent().use { block() }
    } catch (e: Throwable) {
      span.recordException(e)
      span.setStatus(StatusCode.ERROR)
      throw e
    } finally {
      operations.add(
          1,
          Attributes.of(OPERATION_LABEL, operation, PREVIEW_LABEL, preview.toString()),
      )
      span.end()
    }
  }

  /** The verdicts a change that was carried out flipped. */
  fun verdictChanges(becameUnsafe: Int, becameSafe: Int) {
    if (becameUnsafe > 0)
        verdictChanges.add(becameUnsafe.toLong(), Attributes.of(DIRECTION, "to_unsafe"))
    if (becameSafe > 0) verdictChanges.add(becameSafe.toLong(), Attributes.of(DIRECTION, "to_safe"))
  }

  private companion object {
    val OPERATION = AttributeKey.stringKey("runline.allowlist.operation")
    val PREVIEW = AttributeKey.booleanKey("runline.allowlist.preview")
    val ACTOR = AttributeKey.stringKey("runline.actor")
    val OPERATION_LABEL = AttributeKey.stringKey("operation")
    val PREVIEW_LABEL = AttributeKey.stringKey("preview")
    val DIRECTION = AttributeKey.stringKey("direction")
  }
}
