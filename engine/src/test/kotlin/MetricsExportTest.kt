package dev.lawlan.runline.engine

import dev.lawlan.runline.engine.support.OtlpReceiver
import dev.lawlan.runline.engine.traceexport.TraceExportTelemetry
import io.opentelemetry.sdk.OpenTelemetrySdk
import kotlin.test.*

/**
 * Whether the Engine's metrics are exported is the deployer's choice, made with OpenTelemetry's
 * standard settings (#64): the Engine does not override it, and without one nothing is exported.
 * The receiver is a real OTLP/HTTP one on a real socket. The settings are given as system
 * properties, which OpenTelemetry reads the way it reads the environment variables; tests of the
 * class run one after the other and put them back.
 */
class MetricsExportTest {
  private val receiver = OtlpReceiver()
  private val set = mutableListOf<String>()

  private fun setting(name: String, value: String) {
    System.setProperty(name, value)
    set += name
  }

  @AfterTest
  fun close() {
    set.forEach(System::clearProperty)
    receiver.close()
  }

  /** Starts the Engine's telemetry, counts one exported span, and closes it (which sends). */
  private fun runAndClose() {
    val otel = getOpenTelemetry("runline-engine-test") as OpenTelemetrySdk
    TraceExportTelemetry(otel).exported("langfuse", 3)
    otel.close()
  }

  @Test
  fun `with the metrics exporter set, the metrics reach the receiver under their own names`() {
    setting("otel.metrics.exporter", "otlp")
    setting("otel.exporter.otlp.protocol", "http/protobuf")
    setting("otel.exporter.otlp.endpoint", receiver.baseUrl)

    runAndClose()

    assertTrue(
        "runline.trace_export.spans.exported" in receiver.metricNames,
        "${receiver.metricNames}",
    )
    assertTrue("runline.trace_export.exports" in receiver.metricNames, "${receiver.metricNames}")
  }

  @Test
  fun `without a metrics exporter set, nothing is exported`() {
    setting("otel.exporter.otlp.protocol", "http/protobuf")
    setting("otel.exporter.otlp.endpoint", receiver.baseUrl)

    runAndClose()

    assertTrue(receiver.requests.isEmpty(), "${receiver.requests.map { it.path }}")
  }

  @Test
  fun `the metrics exporter can be set to none explicitly`() {
    setting("otel.metrics.exporter", "none")
    setting("otel.exporter.otlp.endpoint", receiver.baseUrl)

    runAndClose()

    assertTrue(receiver.requests.isEmpty())
  }
}
