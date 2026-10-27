package dev.lawlan.runline.engine.traceexport

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/** Why spans did not get to the endpoint; [wire] is the label of the metric. */
enum class DropReason(val wire: String) {
  /** The resource's queue was full when the span was submitted. */
  QUEUE_FULL("queue_full"),

  /** The endpoint did not take the batch: unreachable, refused, too slow or no answer. */
  EXPORT_FAILED("export_failed"),

  /** The credential's alias gave no usable key when the batch was to go out. */
  KEY_UNAVAILABLE("key_unavailable"),

  /** The resource cannot export (its certificates cannot be used), so nothing is queued. */
  UNAVAILABLE("unavailable"),

  /** Still queued when the exporter was closed. */
  ABANDONED("abandoned"),
}

/** What was cut from a span to keep within the limits; [wire] is the label of the metric. */
enum class Truncation(val wire: String) {
  /** An attribute beyond the most a span keeps. */
  COUNT("count"),

  /** A text (a value, a name, a message) cut to the most characters kept. */
  LENGTH("length"),

  /** An attribute whose value is not one of the kinds a span carries. */
  INVALID("invalid"),
}

/**
 * What the Engine records of the spans of `trace-export` resources (ADR-46 decision 4): the spans
 * exported and dropped (with the reason), the export requests by outcome, and what was cut for the
 * limits, each labelled with the resource's name; and a warning in the log, at most one a minute
 * for a resource and a reason, which says how many spans and nothing of them. Nothing of a span's
 * name, attributes or message, and nothing of the credential, is recorded anywhere.
 */
class TraceExportTelemetry(openTelemetry: OpenTelemetry) {
  private val log = LoggerFactory.getLogger(TraceExportTelemetry::class.java)
  private val meter = openTelemetry.getMeter("runline.resources.trace_export")
  private val exportedSpans =
      meter
          .counterBuilder("runline.trace_export.spans.exported")
          .setDescription("Spans the endpoint took")
          .build()
  private val droppedSpans =
      meter
          .counterBuilder("runline.trace_export.spans.dropped")
          .setDescription("Spans that did not get to the endpoint, by reason")
          .build()
  private val exports =
      meter
          .counterBuilder("runline.trace_export.exports")
          .setDescription("Export requests by outcome")
          .build()
  private val truncated =
      meter
          .counterBuilder("runline.trace_export.attributes.truncated")
          .setDescription("Attributes and texts cut to the limits of a span, by kind")
          .build()

  private class Limiter {
    var lastLoggedNanos = 0L
    var logged = false
    var suppressed = 0L
  }

  private val limiters = ConcurrentHashMap<Pair<String, DropReason>, Limiter>()

  fun exported(resource: String, spans: Int) {
    exportedSpans.add(spans.toLong(), resourceLabel(resource))
    exports.add(1, resourceLabel(resource, OUTCOME to "ok"))
  }

  /** [spans] did not get to the endpoint, for [reason]; counted and warned about, not retried. */
  fun dropped(resource: String, spans: Int, reason: DropReason) {
    droppedSpans.add(spans.toLong(), resourceLabel(resource, REASON to reason.wire))
    if (reason == DropReason.EXPORT_FAILED) {
      exports.add(1, resourceLabel(resource, OUTCOME to "failed"))
    } else if (reason == DropReason.KEY_UNAVAILABLE) {
      exports.add(1, resourceLabel(resource, OUTCOME to "key_unavailable"))
    }
    warn(resource, spans, reason)
  }

  fun truncated(resource: String, kind: Truncation, count: Int) {
    if (count > 0) truncated.add(count.toLong(), resourceLabel(resource, KIND to kind.wire))
  }

  /** The warning, unless one was made for the resource and reason less than a minute ago. */
  private fun warn(resource: String, spans: Int, reason: DropReason) {
    val limiter = limiters.computeIfAbsent(resource to reason) { Limiter() }
    val suppressed: Long
    synchronized(limiter) {
      val now = System.nanoTime()
      if (limiter.logged && now - limiter.lastLoggedNanos < WARNING_INTERVAL_NANOS) {
        limiter.suppressed++
        return
      }
      limiter.logged = true
      limiter.lastLoggedNanos = now
      suppressed = limiter.suppressed
      limiter.suppressed = 0
    }
    log.warn(
        "trace-export resource {} dropped {} span(s): {} ({} earlier warning(s) not logged)",
        resource,
        spans,
        reason.wire,
        suppressed,
    )
  }

  private fun resourceLabel(resource: String, vararg more: Pair<AttributeKey<String>, String>) =
      Attributes.builder()
          .put(RESOURCE, resource)
          .also { b -> more.forEach { b.put(it.first, it.second) } }
          .build()

  private companion object {
    const val WARNING_INTERVAL_NANOS = 60_000_000_000L
    val RESOURCE: AttributeKey<String> = AttributeKey.stringKey("resource")
    val OUTCOME: AttributeKey<String> = AttributeKey.stringKey("outcome")
    val REASON: AttributeKey<String> = AttributeKey.stringKey("reason")
    val KIND: AttributeKey<String> = AttributeKey.stringKey("kind")
  }
}
