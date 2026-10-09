package dev.lawlan.runline.engine

import dev.lawlan.runline.engine.support.CapturedLogs
import dev.lawlan.runline.engine.support.OtelCollector
import dev.lawlan.runline.engine.support.StuckServer
import dev.lawlan.runline.engine.support.TestTokens
import dev.lawlan.runline.engine.support.awaitCondition
import dev.lawlan.runline.engine.support.configureEngine
import dev.lawlan.runline.engine.support.unusedPort
import dev.lawlan.runline.engine.support.withSystemProperties
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*

/**
 * Metrics leave the Engine over OTLP like traces and logs, to wherever the standard OpenTelemetry
 * settings of the deployment point (07 "可觀測性", WI-63). The receiving end is a real collector.
 */
class MetricsExportTest {
  @Test
  fun `metrics reach the collector the standard settings point at, sent at the latest on stop`() =
      OtelCollector().use { collector ->
        // The export interval stays the SDK's (a minute): what arrives was sent when the Engine
        // stopped.
        withSystemProperties(
            mapOf(
                "otel.metrics.exporter" to "otlp",
                "otel.traces.exporter" to "otlp",
                "otel.logs.exporter" to "otlp",
                // The collector's OTLP over HTTP port, as in the deployment examples; the Java
                // SDK's own default protocol is gRPC.
                "otel.exporter.otlp.protocol" to "http/protobuf",
                "otel.exporter.otlp.endpoint" to collector.endpoint,
            )
        ) {
          var stopping = 0L
          testApplication {
            configureEngine()
            startApplication()
            val defined =
                client.post("/api/v1/resources") {
                  header(HttpHeaders.Authorization, "Bearer ${TestTokens.ROOT}")
                  contentType(ContentType.Application.Json)
                  setBody("""{"name":"exported","capacity":1}""")
                }
            assertEquals(HttpStatusCode.Created, defined.status, defined.bodyAsText())
            val checked =
                client.post("/api/v1/resources/exported/check") {
                  header(HttpHeaders.Authorization, "Bearer ${TestTokens.ROOT}")
                }
            assertEquals(HttpStatusCode.OK, checked.status, checked.bodyAsText())
            stopping = System.nanoTime()
          }
          val stopMillis = (System.nanoTime() - stopping) / 1_000_000
          // The test Engine's grace time (runs.shutdownGraceSeconds in configureEngine).
          assertTrue(stopMillis < 5_000, "stopping took $stopMillis ms")
        }

        awaitCondition(
            "a run metric and a resource metric, with their labels, at the collector",
            diagnostics = { collector.logs },
        ) {
          val metrics = collector.metrics()
          // 07: the number of runs in progress.
          metrics.any { it.name == "runline.runs.active" } &&
              // 07: the result of a check of a resource's entity, labelled by name and type.
              metrics.any {
                it.name == "runline.resources.checks" &&
                    "resource: Str(exported)" in it.attributes &&
                    "type: Str(counter)" in it.attributes
              }
        }
      }

  @Test
  fun `with no collector listening, an idle Engine goes on serving and its log stays short`() =
      // Exported every second instead of every minute; each export is tried again as the SDK does
      // (its retries take seconds), and every attempt fails.
      withSystemProperties(
          mapOf(
              "otel.metrics.exporter" to "otlp",
              "otel.traces.exporter" to "otlp",
              "otel.logs.exporter" to "otlp",
              "otel.exporter.otlp.protocol" to "http/protobuf",
              "otel.exporter.otlp.endpoint" to "http://127.0.0.1:${unusedPort()}",
              "otel.metric.export.interval" to "1000",
          )
      ) {
        CapturedLogs().use { logs ->
          OpenTelemetryLog().use { exporterLog ->
            testApplication {
              configureEngine()
              startApplication()
              val started = logs.lines.size
              val exporterStarted = exporterLog.records.size

              Thread.sleep(IDLE_MILLIS)

              val ready = client.get("/api/v1/health/ready")
              assertEquals(HttpStatusCode.OK, ready.status, ready.bodyAsText())
              // The test host announces its connectors when it gets to it; those lines are not
              // the Engine's.
              val engineLines = logs.lines.drop(started).filterNot { it.startsWith(TEST_HOST) }
              val exporterLines = exporterLog.records.drop(exporterStarted)
              assertTrue(
                  engineLines.size + exporterLines.size <= MAX_LINES,
                  (engineLines + exporterLines).joinToString("\n"),
              )
              // The failures were there to be written: the exporter did try.
              assertTrue(exporterLines.isNotEmpty(), "no failed export was reported")
            }
          }
        }
      }

  @Test
  fun `with a collector that never answers, stopping takes no longer than the grace time`() =
      StuckServer(reply = "").use { collector ->
        withSystemProperties(
            mapOf(
                "otel.metrics.exporter" to "otlp",
                "otel.traces.exporter" to "otlp",
                "otel.logs.exporter" to "otlp",
                "otel.exporter.otlp.protocol" to "http/protobuf",
                "otel.exporter.otlp.endpoint" to collector.base,
            )
        ) {
          var stopping = 0L
          testApplication {
            configureEngine(overrides = mapOf("runs.shutdownGraceSeconds" to "$GRACE_SECONDS"))
            startApplication()
            // A span and the metrics of a request, waiting to be sent.
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/health/ready").status)
            stopping = System.nanoTime()
          }
          val stopMillis = (System.nanoTime() - stopping) / 1_000_000
          // The test host's own stopping is in the margin.
          assertTrue(
              stopMillis < GRACE_SECONDS * 1_000 + MARGIN_MILLIS,
              "stopping took $stopMillis ms",
          )
        }
      }

  /**
   * What the OpenTelemetry SDK writes about itself (to `java.util.logging`, which the Engine leaves
   * on standard error): each record as one text.
   */
  private class OpenTelemetryLog : AutoCloseable {
    private val logger = java.util.logging.Logger.getLogger("io.opentelemetry")
    private val captured = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val handler =
        object : java.util.logging.Handler() {
          override fun publish(record: java.util.logging.LogRecord) {
            captured.add("${record.level} ${record.loggerName} ${record.message}")
          }

          override fun flush() = Unit

          override fun close() = Unit
        }

    init {
      logger.addHandler(handler)
    }

    val records: List<String>
      get() = synchronized(captured) { captured.toList() }

    override fun close() = logger.removeHandler(handler)
  }

  private companion object {
    const val IDLE_MILLIS = 12_000L
    const val TEST_HOST = "INFO io.ktor.test "
    /** The SDK reports at most five failures a minute (its ThrottlingLogger), then one an hour. */
    const val MAX_LINES = 10
    const val GRACE_SECONDS = 2L
    const val MARGIN_MILLIS = 1_000L
  }
}
