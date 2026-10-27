package dev.lawlan.runline.engine.support

import dev.lawlan.runline.accessors.ResourceBinding
import dev.lawlan.runline.engine.config.TraceExportTuning
import dev.lawlan.runline.engine.resource.ResourceBehavior
import dev.lawlan.runline.engine.resource.ResourceType
import dev.lawlan.runline.engine.resource.SharedResource
import dev.lawlan.runline.engine.resource.TraceExportBehavior
import dev.lawlan.runline.engine.secret.NoSecretStore
import dev.lawlan.runline.engine.secret.SecretStore
import dev.lawlan.runline.engine.traceexport.TraceExportTelemetry
import dev.lawlan.runline.engine.traceexport.TraceExporters
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.data.LongPointData
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Tuning that makes tests quick: batches go out after 50 ms and nothing waits for long. */
val QUICK_TRACE_EXPORT =
    TraceExportTuning(
        scheduleDelay = Duration.ofMillis(50),
        exportTimeout = Duration.ofSeconds(2),
        flushWait = Duration.ofSeconds(2),
    )

/**
 * The behavior of `trace-export` resources assembled from its real parts, to be bound and called
 * the way the Engine's gate does: [bind] a resource, [submit] spans through the binding. What the
 * Engine records is read from OpenTelemetry's in-memory metric reader.
 */
class TraceExportRig(
    secrets: SecretStore = NoSecretStore,
    val tuning: TraceExportTuning = QUICK_TRACE_EXPORT,
) : AutoCloseable {
  private val reader = InMemoryMetricReader.create()
  private val openTelemetry =
      OpenTelemetrySdk.builder()
          .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(reader).build())
          .build()
  val exporters = TraceExporters(secrets, tuning, TraceExportTelemetry(openTelemetry))
  val behavior: ResourceBehavior = TraceExportBehavior(secrets, Duration.ofSeconds(5), exporters)
  private val bindings = mutableListOf<ResourceBinding>()

  fun resource(
      endpoint: String,
      serviceName: String = "transcribe",
      alias: String? = "langfuse-auth",
      name: String = "langfuse",
  ) =
      SharedResource(
          name,
          1_000_000,
          true,
          "root",
          Instant.EPOCH,
          "root",
          Instant.EPOCH,
          ResourceType.TRACE_EXPORT,
          JsonObject(
              mapOf(
                  "endpoint" to JsonPrimitive(endpoint),
                  "serviceName" to JsonPrimitive(serviceName),
              )
          ),
          alias,
      )

  /** The binding a run gets for [resource]. */
  fun bind(resource: SharedResource): ResourceBinding =
      checkNotNull(behavior.bind(resource)) { "no binding" }.also { bindings += it }

  fun submit(
      binding: ResourceBinding,
      name: String,
      id: String,
      parentId: String? = null,
      start: Instant = Instant.parse("2026-10-27T10:00:00Z"),
      end: Instant = start.plusMillis(250),
      status: String = "OK",
      statusMessage: String? = null,
      attributes: Map<String, Any?> = emptyMap(),
  ) {
    val arguments = HashMap<String, Any?>()
    arguments["name"] = name
    arguments["id"] = id
    arguments["parentId"] = parentId
    arguments["startTime"] = start
    arguments["endTime"] = end
    arguments["status"] = status
    arguments["statusMessage"] = statusMessage
    arguments["attributes"] = HashMap(attributes)
    binding.execute("trace.submit", arguments)
  }

  /**
   * The sum of the counter [name] over the data points whose labels include [labels]; zero when
   * nothing was recorded.
   */
  fun count(name: String, vararg labels: Pair<String, String>): Long =
      reader
          .collectAllMetrics()
          .filter { it.name == name }
          .flatMap { it.longSumData.points }
          .filter { point ->
            labels.all { (k, v) -> point.attributes.get(AttributeKey.stringKey(k)) == v }
          }
          .sumOf { (it as LongPointData).value }

  /** Every name and label of everything recorded, as text, for tests of what must not be there. */
  fun recordedText(): String =
      reader.collectAllMetrics().joinToString("\n") { metric ->
        metric.name +
            " " +
            metric.longSumData.points.joinToString { it.attributes.toString() } +
            " " +
            metric.description
      }

  override fun close() {
    bindings.forEach { runCatching { it.close() } }
    exporters.close()
  }
}
