package dev.lawlan.runline.engine.traceexport

import dev.lawlan.runline.accessors.ResourceBinding
import dev.lawlan.runline.accessors.ResourceOperationFailure
import dev.lawlan.runline.core.ResourceFailure
import dev.lawlan.runline.engine.config.TraceExportTuning
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.HexFormat

/**
 * The accessor of one run for a `trace-export` resource (ADR-46): the host's side of the operation
 * `trace.submit`. A span is taken apart and checked here (the host trusts nothing of the call but
 * its own reading of it), given the identifiers the Engine makes (the run's trace, an identifier
 * for the span and one for its parent, both made from the names the pipeline used), kept within the
 * limits and handed to the exporter's queue, which answers at once.
 *
 * Nothing of a span is ever in a failure or a message: a call that is not what it should be fails
 * with the category `INVALID_ARGUMENT` and nothing else.
 */
internal class TraceExportBinding(
    private val resource: String,
    private val sink: SpanSink,
    private val tuning: TraceExportTuning,
    private val telemetry: TraceExportTelemetry,
    private val onClose: () -> Unit = {},
) : ResourceBinding {
  override val type = "trace-export"

  /** One trace for the whole run: the Engine's choice, never the pipeline's. */
  private val traceId = randomHex(16)

  override fun execute(operation: String, arguments: Map<String, Any?>): Any? {
    if (operation != SUBMIT) throw ResourceOperationFailure(ResourceFailure.INVALID_ARGUMENT)
    val span = pendingSpanOf(arguments)
    sink.offer(span)?.let { telemetry.dropped(resource, 1, it) }
    return null
  }

  override fun close() {
    // The run does not wait for the endpoint: what is queued is sent now, in the exporter's time.
    try {
      sink.requestFlush()
    } finally {
      onClose()
    }
  }

  private fun pendingSpanOf(arguments: Map<String, Any?>): PendingSpan {
    val name = arguments["name"] as? String ?: invalid()
    val id = arguments["id"] as? String ?: invalid()
    val parentId = arguments["parentId"]?.let { it as? String ?: invalid() }
    val start = (arguments["startTime"] as? Instant ?: invalid()).toEpochNanos()
    val end = (arguments["endTime"] as? Instant ?: invalid()).toEpochNanos()
    val error =
        when (arguments["status"]) {
          "OK" -> false
          "ERROR" -> true
          else -> invalid()
        }
    val message = arguments["statusMessage"]?.let { it as? String ?: invalid() }.orEmpty()
    val given = arguments["attributes"] as? Map<*, *> ?: invalid()
    val cuts = Cuts(tuning)
    val span =
        PendingSpan(
            name = cuts.text(name),
            traceId = traceId,
            spanId = identifierOf(id),
            parentSpanId = parentId?.takeIf { it.isNotEmpty() && it != id }?.let(::identifierOf),
            startEpochNanos = start,
            endEpochNanos = end,
            error = error,
            statusMessage = cuts.text(message),
            attributes = attributesOf(given, cuts),
        )
    telemetry.truncated(resource, Truncation.LENGTH, cuts.texts)
    telemetry.truncated(resource, Truncation.ELEMENTS, cuts.lists)
    return span
  }

  /**
   * The attributes that are kept: in the order of their keys, at most the limit of them, each a
   * string, boolean, long, double or a list of one of these, with texts cut to the limit. What is
   * not kept is counted.
   */
  private fun attributesOf(given: Map<*, *>, cuts: Cuts): Map<String, Any> {
    val kept = LinkedHashMap<String, Any>()
    var beyondLimit = 0
    var invalid = 0
    for (key in given.keys.filterIsInstance<String>().sorted()) {
      val value = carry(given[key], cuts)
      when {
        value == null -> invalid++
        kept.size >= tuning.maxAttributes -> beyondLimit++
        else -> kept[cuts.text(key)] = value
      }
    }
    invalid += given.keys.count { it !is String }
    telemetry.truncated(resource, Truncation.COUNT, beyondLimit)
    telemetry.truncated(resource, Truncation.INVALID, invalid)
    return kept
  }

  private fun carry(value: Any?, cuts: Cuts): Any? =
      when (value) {
        is List<*> -> carryList(value, cuts)
        else -> carryScalar(value, cuts)
      }

  private fun carryScalar(value: Any?, cuts: Cuts): Any? =
      when (value) {
        is String -> cuts.text(value)
        is Boolean -> value
        is Long -> value
        is Double -> value
        else -> null
      }

  private fun carryList(value: List<*>, cuts: Cuts): Any? {
    val kept = minOf(value.size, tuning.maxAttributeElements)
    if (kept < value.size) cuts.lists++
    val carried = ArrayList<Any>(kept)
    for (i in 0 until kept) carried += carryScalar(value[i], cuts) ?: return null
    return if (carried.map { it.javaClass }.toSet().size <= 1) carried else null
  }

  /** What was cut from one span: texts to the length limit, lists to the element limit. */
  private class Cuts(private val tuning: TraceExportTuning) {
    var texts = 0
    var lists = 0

    fun text(value: String): String =
        if (value.length <= tuning.maxAttributeLength) value
        else value.substring(0, tuning.maxAttributeLength).also { texts++ }
  }

  /** Eight bytes made from the trace and the name the pipeline gave: the same name, the same id. */
  private fun identifierOf(localId: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest("$traceId:$localId".toByteArray())
    val bytes = digest.copyOf(8)
    if (bytes.all { it == 0.toByte() }) bytes[7] = 1
    return HEX.formatHex(bytes)
  }

  private fun Instant.toEpochNanos(): Long =
      try {
        Math.addExact(Math.multiplyExact(epochSecond, 1_000_000_000L), nano.toLong())
      } catch (e: ArithmeticException) {
        invalid()
      }

  private fun invalid(): Nothing = throw ResourceOperationFailure(ResourceFailure.INVALID_ARGUMENT)

  private companion object {
    const val SUBMIT = "trace.submit"
    val HEX: HexFormat = HexFormat.of()
    val RANDOM = SecureRandom()

    fun randomHex(bytes: Int): String {
      val value = ByteArray(bytes)
      do {
        RANDOM.nextBytes(value)
      } while (value.all { it == 0.toByte() })
      return HEX.formatHex(value)
    }
  }
}
