package dev.lawlan.runline.engine.view

import dev.lawlan.runline.engine.support.CapturedLogs
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlin.test.*

/**
 * What the Engine records of a read of a view (WI-1301): a log line, a trace and metrics for each,
 * made of the view's identity, the result, the time and the rows, and nothing else.
 */
class ViewTelemetryTest {
  private val spans = InMemorySpanExporter.create()
  private val metrics = InMemoryMetricReader.create()
  private val logs = CapturedLogs()
  private val otel =
      OpenTelemetrySdk.builder()
          .setTracerProvider(
              SdkTracerProvider.builder()
                  .addSpanProcessor(SimpleSpanProcessor.create(spans))
                  .build()
          )
          .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metrics).build())
          .build()
  private val telemetry = ViewTelemetry(otel)

  @AfterTest fun close() = logs.close()

  private fun points(name: String) = metrics.collectAllMetrics().firstOrNull { it.name == name }

  private fun attributes(map: Map<io.opentelemetry.api.common.AttributeKey<*>, Any>) =
      map.mapKeys { it.key.key }.mapValues { "${it.value}" }

  @Test
  fun `a read is a log line, a span and metrics with the view, the result, the time and the rows`() {
    telemetry.begin("feed", "articles", "alice", "bob").end("ok", rows = 3)

    val line = logs.lines.single { "View read" in it }
    for (part in
        listOf(
            "pipeline=feed",
            "view=articles",
            "uploader=alice",
            "caller=bob",
            "result=ok",
            "rows=3",
            "durationMs=",
        )) {
      assertTrue(part in line, "$part not in $line")
    }
    val span = spans.finishedSpanItems.single()
    assertEquals("runline.views.read", span.name)
    val seen = attributes(span.attributes.asMap())
    assertEquals("feed", seen["runline.view.pipeline"])
    assertEquals("articles", seen["runline.view.id"])
    assertEquals("ok", seen["runline.view.result"])
    assertEquals("3", seen["runline.view.rows"])
    assertEquals(io.opentelemetry.api.trace.StatusCode.UNSET, span.status.statusCode)
    val count = points("runline.views.reads")!!.longSumData.points.single()
    assertEquals(1, count.value)
    assertEquals(
        mapOf("pipeline" to "feed", "view" to "articles", "result" to "ok"),
        attributes(count.attributes.asMap()),
    )
    assertEquals(1, points("runline.views.read.duration")!!.histogramData.points.single().count)
    assertEquals(3.0, points("runline.views.read.rows")!!.histogramData.points.single().sum)
    assertNull(points("runline.views.read.failures"))
  }

  @Test
  fun `a read that failed is counted as a failure by its result, and its span says so`() {
    telemetry.begin("feed", "articles", "alice", "alice").end("timeout")
    telemetry.begin("feed", "articles", "alice", "alice").end("invalid_parameter")

    val failures = points("runline.views.read.failures")!!.longSumData.points
    assertEquals(
        mapOf("timeout" to 1L, "invalid_parameter" to 1L),
        failures.associate { attributes(it.attributes.asMap()).getValue("result") to it.value },
    )
    val statuses =
        spans.finishedSpanItems.associate {
          attributes(it.attributes.asMap()).getValue("runline.view.result") to it.status.statusCode
        }
    assertEquals(io.opentelemetry.api.trace.StatusCode.ERROR, statuses["timeout"])
    assertEquals(io.opentelemetry.api.trace.StatusCode.UNSET, statuses["invalid_parameter"])
    assertTrue(
        logs.lines.any { "result=timeout" in it && "rows=" !in it.substringAfter("result=timeout") }
    )
  }
}
