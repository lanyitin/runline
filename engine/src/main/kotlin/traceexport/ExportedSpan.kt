package dev.lawlan.runline.engine.traceexport

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.sdk.common.InstrumentationLibraryInfo
import io.opentelemetry.sdk.common.InstrumentationScopeInfo
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.data.EventData
import io.opentelemetry.sdk.trace.data.LinkData
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.data.StatusData

/**
 * A finished span a run submitted, as the Engine holds it while it waits for its batch: JDK values
 * only, with the identifiers the Engine made (the run's trace, and an identifier of its own for the
 * span and for its parent). The values of [attributes] are strings, booleans, longs, doubles or
 * lists of one of these.
 */
internal class PendingSpan(
    val name: String,
    val traceId: String,
    val spanId: String,
    /** Null for a root span. */
    val parentSpanId: String?,
    val startEpochNanos: Long,
    val endEpochNanos: Long,
    val error: Boolean,
    val statusMessage: String,
    val attributes: Map<String, Any>,
)

/** A [PendingSpan] as an OpenTelemetry exporter takes it; no OpenTelemetry type is held earlier. */
internal class ExportedSpan(
    private val span: PendingSpan,
    private val resource: Resource,
    private val scope: InstrumentationScopeInfo,
) : SpanData {
  private val context =
      SpanContext.create(
          span.traceId,
          span.spanId,
          TraceFlags.getSampled(),
          TraceState.getDefault(),
      )
  private val parent =
      span.parentSpanId?.let {
        SpanContext.create(span.traceId, it, TraceFlags.getSampled(), TraceState.getDefault())
      } ?: SpanContext.getInvalid()
  private val attributesOf: Attributes by lazy { toAttributes(span.attributes) }

  override fun getName(): String = span.name

  override fun getKind(): SpanKind = SpanKind.INTERNAL

  override fun getSpanContext(): SpanContext = context

  override fun getParentSpanContext(): SpanContext = parent

  override fun getStatus(): StatusData =
      if (span.error) StatusData.create(StatusCode.ERROR, span.statusMessage) else StatusData.ok()

  override fun getStartEpochNanos(): Long = span.startEpochNanos

  override fun getAttributes(): Attributes = attributesOf

  override fun getEvents(): List<EventData> = emptyList()

  override fun getLinks(): List<LinkData> = emptyList()

  override fun getEndEpochNanos(): Long = span.endEpochNanos

  override fun hasEnded(): Boolean = true

  override fun getTotalRecordedEvents(): Int = 0

  override fun getTotalRecordedLinks(): Int = 0

  override fun getTotalAttributeCount(): Int = span.attributes.size

  @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
  override fun getInstrumentationLibraryInfo(): InstrumentationLibraryInfo =
      InstrumentationLibraryInfo.create(scope.name, scope.version)

  override fun getInstrumentationScopeInfo(): InstrumentationScopeInfo = scope

  override fun getResource(): Resource = resource

  private companion object {
    fun toAttributes(values: Map<String, Any>): Attributes {
      val builder = Attributes.builder()
      for ((key, value) in values) {
        when (value) {
          is String -> builder.put(AttributeKey.stringKey(key), value)
          is Boolean -> builder.put(AttributeKey.booleanKey(key), value)
          is Long -> builder.put(AttributeKey.longKey(key), value)
          is Double -> builder.put(AttributeKey.doubleKey(key), value)
          is List<*> -> putList(builder, key, value)
        }
      }
      return builder.build()
    }

    @Suppress("UNCHECKED_CAST")
    fun putList(
        builder: io.opentelemetry.api.common.AttributesBuilder,
        key: String,
        list: List<*>,
    ) {
      when (list.firstOrNull()) {
        is Boolean -> builder.put(AttributeKey.booleanArrayKey(key), list as List<Boolean>)
        is Long -> builder.put(AttributeKey.longArrayKey(key), list as List<Long>)
        is Double -> builder.put(AttributeKey.doubleArrayKey(key), list as List<Double>)
        else -> builder.put(AttributeKey.stringArrayKey(key), list as List<String>)
      }
    }
  }
}
