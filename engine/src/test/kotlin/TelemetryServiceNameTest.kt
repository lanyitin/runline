package dev.lawlan.runline.engine

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.core.ConsoleAppender
import dev.lawlan.runline.engine.support.configureEngine
import io.ktor.server.plugins.di.*
import io.ktor.server.testing.*
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.ReadableSpan
import io.opentelemetry.semconv.ServiceAttributes
import kotlin.test.*
import org.slf4j.LoggerFactory

/** Traces, metrics and logs name the Engine the same way, and the name comes from configuration. */
class TelemetryServiceNameTest {
  private fun serviceNameOfSpans(otel: OpenTelemetry): String? {
    val span = otel.getTracer("test").spanBuilder("probe").startSpan()
    try {
      return (span as ReadableSpan)
          .toSpanData()
          .resource
          .getAttribute(ServiceAttributes.SERVICE_NAME)
    } finally {
      span.end()
    }
  }

  /** What the Engine's own console appender writes for one log event. */
  private fun consoleLine(): String {
    val context = LoggerFactory.getILoggerFactory() as LoggerContext
    val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
    val console =
        root.getAppender("STDOUT") as ConsoleAppender<ch.qos.logback.classic.spi.ILoggingEvent>
    val event = LoggingEvent("x", context.getLogger("probe"), Level.INFO, "hello", null, null)
    return String(console.encoder.encode(event))
  }

  @Test
  fun `the Engine is named runline-engine by default in traces, metrics and logs`() =
      testApplication {
        configureEngine()
        startApplication()
        val otel = application.dependencies.resolve<OpenTelemetry>()

        assertEquals("runline-engine", serviceNameOfSpans(otel))
        assertTrue(
            (otel as OpenTelemetrySdk).sdkMeterProvider.toString().contains("runline-engine")
        )
        assertTrue(consoleLine().contains("runline-engine"), consoleLine())
      }

  @Test
  fun `the name can be set and traces, metrics and logs all follow it`() = testApplication {
    configureEngine(overrides = mapOf("telemetry.serviceName" to "runline-engine-staging"))
    startApplication()
    val otel = application.dependencies.resolve<OpenTelemetry>()

    assertEquals("runline-engine-staging", serviceNameOfSpans(otel))
    val meters = (otel as OpenTelemetrySdk).sdkMeterProvider.toString()
    assertTrue(meters.contains("runline-engine-staging"), meters)
    assertTrue(consoleLine().contains("runline-engine-staging"), consoleLine())
    assertFalse(meters.contains("ktor-sample"))
  }
}
