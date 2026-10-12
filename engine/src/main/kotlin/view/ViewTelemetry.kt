package dev.lawlan.runline.engine.view

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import org.slf4j.LoggerFactory

/**
 * What the Engine records of the reads of views (WI-1301, ADR-033): for each, a line of the log, a
 * span of the trace and the metrics, made of the identity of the view, the result, the time and the
 * number of rows. Never the text of a query, a parameter or a value of a row: nothing here is given
 * any of them.
 */
class ViewTelemetry(openTelemetry: OpenTelemetry) {
  private val log = LoggerFactory.getLogger(ViewTelemetry::class.java)
  private val tracer = openTelemetry.getTracer("runline.views")
  private val meter = openTelemetry.getMeter("runline.views")
  private val reads =
      meter
          .counterBuilder("runline.views.reads")
          .setDescription("Reads of views, by result")
          .build()
  private val failures =
      meter
          .counterBuilder("runline.views.read.failures")
          .setDescription("Reads of views that did not give rows, by result")
          .build()
  private val duration =
      meter
          .histogramBuilder("runline.views.read.duration")
          .setUnit("s")
          .setDescription("Time of a read of a view")
          .build()
  private val rows =
      meter
          .histogramBuilder("runline.views.read.rows")
          .ofLongs()
          .setDescription("Rows of the page a read of a view gave")
          .build()

  /**
   * Begins to observe a read of [view] of [pipeline] (of [uploader]'s version) asked by [caller].
   */
  fun begin(pipeline: String, view: String, uploader: String, caller: String): ViewObservation {
    val span =
        tracer
            .spanBuilder("runline.views.read")
            .setAttribute(PIPELINE, pipeline)
            .setAttribute(VIEW, view)
            .startSpan()
    return ViewObservation(System.nanoTime()) { result, rowCount, nanos ->
      finish(span, pipeline, view, uploader, caller, result, rowCount, nanos)
    }
  }

  private fun finish(
      span: Span,
      pipeline: String,
      view: String,
      uploader: String,
      caller: String,
      result: String,
      rowCount: Int?,
      nanos: Long,
  ) {
    span.setAttribute(RESULT, result)
    rowCount?.let { span.setAttribute(ROWS, it.toLong()) }
    if (result in SERVER_SIDE) span.setStatus(StatusCode.ERROR)
    span.end()
    val labels = Attributes.of(PIPELINE_LABEL, pipeline, VIEW_LABEL, view, RESULT_LABEL, result)
    reads.add(1, labels)
    if (result != OK) failures.add(1, labels)
    duration.record(nanos / 1e9, labels)
    rowCount?.let { rows.record(it.toLong(), labels) }
    val millis = nanos / 1_000_000
    val message =
        "View read: pipeline={} view={} uploader={} caller={} result={} durationMs={}" +
            (if (rowCount != null) " rows={}" else "")
    val arguments: List<Any> =
        listOfNotNull(pipeline, view, uploader, caller, result, millis, rowCount)
    if (result in SERVER_SIDE) log.warn(message, *arguments.toTypedArray())
    else log.info(message, *arguments.toTypedArray())
  }

  private companion object {
    const val OK = "ok"

    /** The results that are the Engine's or the database's trouble, not the caller's. */
    val SERVER_SIDE = setOf("timeout", "connection_failed", "failed")
    val PIPELINE = AttributeKey.stringKey("runline.view.pipeline")
    val VIEW = AttributeKey.stringKey("runline.view.id")
    val RESULT = AttributeKey.stringKey("runline.view.result")
    val ROWS = AttributeKey.longKey("runline.view.rows")
    val PIPELINE_LABEL = AttributeKey.stringKey("pipeline")
    val VIEW_LABEL = AttributeKey.stringKey("view")
    val RESULT_LABEL = AttributeKey.stringKey("result")
  }
}

/** One read being observed; [end] records it, once, with its result. */
class ViewObservation
internal constructor(
    private val started: Long,
    private val finish: (String, Int?, Long) -> Unit,
) {
  private var ended = false

  fun end(result: String, rows: Int? = null) {
    if (ended) return
    ended = true
    finish(result, rows, System.nanoTime() - started)
  }
}
