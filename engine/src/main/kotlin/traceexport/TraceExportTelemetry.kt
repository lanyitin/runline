package dev.lawlan.runline.engine.traceexport

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
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

  /** Elements of a list attribute beyond the most a list keeps. */
  ELEMENTS("elements"),

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
class TraceExportTelemetry(
    openTelemetry: OpenTelemetry,
    /** How long a warning for a resource and a reason suppresses the ones that follow it. */
    private val warningInterval: java.time.Duration = java.time.Duration.ofMinutes(1),
) {
  private val log = LoggerFactory.getLogger(TraceExportTelemetry::class.java)

  init {
    // The thread of the warnings is started here, by whoever sets the Engine up, not by a run.
    WarningLog.start()
  }

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

  /**
   * The open windows: a warning for a resource and a reason opens one, and the warnings that come
   * before it ends are held back; the value is how many.
   */
  private val windows = ConcurrentHashMap<Pair<String, DropReason>, Long>()

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

  /**
   * The warning, unless a window is open for the resource and the reason; then it is held back, and
   * the number held back is said when the window ends, so it counts that window and nothing else.
   * The words are written by the thread of [WarningLog], not by the caller: a run that fills a
   * queue calls from its own thread, and what that thread writes is the log of the run.
   */
  private fun warn(resource: String, spans: Int, reason: DropReason) {
    val key = resource to reason
    var opened = false
    windows.compute(key) { _, held ->
      if (held == null) {
        opened = true
        0L
      } else {
        held + 1
      }
    }
    if (!opened) return
    WarningLog.write {
      log.warn("trace-export resource {} dropped {} span(s): {}", resource, spans, reason.wire)
    }
    WarningLog.later(warningInterval) { closeWindow(key) }
  }

  private fun closeWindow(key: Pair<String, DropReason>) {
    val held = windows.remove(key) ?: return
    if (held > 0) {
      log.warn(
          "trace-export resource {} held back {} further warning(s) of dropped spans ({}) in the last window",
          key.first,
          held,
          key.second.wire,
      )
    }
  }

  private fun resourceLabel(resource: String, vararg more: Pair<AttributeKey<String>, String>) =
      Attributes.builder()
          .put(RESOURCE, resource)
          .also { b -> more.forEach { b.put(it.first, it.second) } }
          .build()

  private companion object {
    val RESOURCE: AttributeKey<String> = AttributeKey.stringKey("resource")
    val OUTCOME: AttributeKey<String> = AttributeKey.stringKey("outcome")
    val REASON: AttributeKey<String> = AttributeKey.stringKey("reason")
    val KIND: AttributeKey<String> = AttributeKey.stringKey("kind")
  }
}

/**
 * The one thread that writes the warnings of [TraceExportTelemetry]. A thread that belongs to a run
 * writes its output into the log of the run, and so does everything it starts; the thread here is
 * started by the Engine (see [start]) so its output is the Engine's.
 */
internal object WarningLog {
  private val executor =
      ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "trace-export-warnings").also { it.isDaemon = true }
          }
          .also { it.prestartCoreThread() }

  /** Makes sure the thread exists; called where the Engine is set up. */
  fun start() = Unit

  fun write(line: () -> Unit) {
    executor.execute { runCatching(line) }
  }

  fun later(delay: java.time.Duration, line: () -> Unit) {
    executor.schedule({ runCatching(line) }, delay.toNanos(), TimeUnit.NANOSECONDS)
  }
}
