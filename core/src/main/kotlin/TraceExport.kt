package dev.lawlan.runline.core

import java.time.Instant

/** Whether the work a span describes succeeded. */
enum class SpanStatus {
  OK,
  ERROR,
}

/**
 * A finished span, as a pipeline hands it over (ADR-46). Only JDK types, so it crosses the boundary
 * as it is. [id] is a name the pipeline makes up for the span within its run and [parentId] the
 * [id] of its parent (`null` for a root); the trace the span belongs to is the Engine's to assign,
 * never the pipeline's. [attributes] values are strings, booleans, integers, floating point numbers
 * or lists of one of these kinds; any other value is dropped when the span is submitted.
 */
class SpanRecord
@JvmOverloads
constructor(
    val name: String,
    val startTime: Instant,
    val endTime: Instant,
    val status: SpanStatus,
    /** Why the span failed; `null` for none. */
    val statusMessage: String?,
    val attributes: Map<String, Any?>,
    val id: String,
    val parentId: String? = null,
)

/**
 * Where a pipeline sends its finished spans (ADR-46). A span is submitted once, when it is over:
 * there is no opening and closing. A submission returns at once and never fails the pipeline.
 */
interface TraceExportAccessor {
  /** Hands [span] over; returns how many of its attributes were dropped for an invalid value. */
  fun submit(span: SpanRecord): Int
}

/** One `trace-export` accessor: a span is one call to the host, whose answer is not looked at. */
internal class HostTraceExport(private val name: String, private val link: ResourceLink?) :
    TraceExportAccessor {
  // Tracing never fails the run: whatever goes wrong here or on the host, the pipeline goes on.
  override fun submit(span: SpanRecord): Int =
      try {
        send(span)
      } catch (_: RuntimeException) {
        0
      }

  private fun send(span: SpanRecord): Int {
    val attributes = java.util.HashMap<String, Any?>()
    var dropped = 0
    for ((key, value) in span.attributes) {
      val carried = carry(value)
      if (carried == null) dropped++ else attributes[key] = carried
    }
    val arguments = java.util.HashMap<String, Any?>()
    arguments["name"] = span.name
    arguments["startTime"] = span.startTime
    arguments["endTime"] = span.endTime
    arguments["status"] = span.status.name
    arguments["statusMessage"] = span.statusMessage
    arguments["id"] = span.id
    arguments["parentId"] = span.parentId
    arguments["attributes"] = attributes
    val request = java.util.HashMap<String, Any?>()
    request["resource"] = name
    request["operation"] = "trace.submit"
    request["arguments"] = arguments
    // A host that cannot take the span loses it; what was dropped here is still told.
    try {
      link?.call?.apply(request)
    } catch (_: RuntimeException) {}
    return dropped
  }

  /** An attribute value in the few shapes the host takes; `null` for any other. */
  private fun carry(value: Any?): Any? =
      when (value) {
        is List<*> -> carryList(value)
        else -> carryScalar(value)
      }

  private fun carryScalar(value: Any?): Any? =
      when (value) {
        is String,
        is Boolean -> value
        is Byte,
        is Short,
        is Int,
        is Long -> (value as Number).toLong()
        is Float,
        is Double -> (value as Number).toDouble()
        else -> null
      }

  /** A list of one kind of scalar; a list of mixed or other values is not carried. */
  private fun carryList(value: List<*>): Any? {
    val carried = java.util.ArrayList<Any?>(value.size)
    for (element in value) carried += carryScalar(element) ?: return null
    val kinds = carried.map { it!!.javaClass }.toSet()
    return if (kinds.size <= 1) carried else null
  }
}
