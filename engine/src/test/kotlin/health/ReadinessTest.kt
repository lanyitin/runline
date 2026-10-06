package dev.lawlan.runline.engine.health

import ch.qos.logback.classic.Level
import dev.lawlan.runline.engine.support.CapturedLogs
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import java.nio.file.Files
import kotlin.test.*

/** What a failed check leaves behind: a reason in the log and a count, nothing in the report. */
class ReadinessTest {
  private val reader = InMemoryMetricReader.create()
  private val otel =
      OpenTelemetrySdk.builder()
          .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(reader).build())
          .build()
  private val logs = CapturedLogs()
  private val lifecycle = EngineLifecycle().also { it.completeStartup() }
  private val missingDirectory = Files.createTempDirectory("readiness").resolve("gone")
  private val readiness =
      Readiness(
          listOf(RuntimeCheck(missingDirectory, emptyList()), ShutdownCheck(lifecycle)),
          lifecycle,
          HealthTelemetry(otel),
      )

  @AfterTest fun close() = logs.close()

  private fun failures(check: String): Long =
      reader
          .collectAllMetrics()
          .filter { it.name == "runline.health.check.failures" }
          .flatMap { it.longSumData.points }
          .filter { it.attributes.get(AttributeKey.stringKey("check")) == check }
          .sumOf { it.value }

  @Test
  fun `the reason a check failed is in the log and not in the report`() {
    val report = readiness.evaluate()

    assertEquals(
        mapOf("runtime" to CheckState.FAILED, "shutdown" to CheckState.OK),
        report.checks,
    )
    val warnings = logs.at(Level.WARN)
    assertTrue(
        warnings.any { it.contains("runtime") && it.contains(missingDirectory.toString()) },
        "$warnings",
    )
  }

  @Test
  fun `every probe that finds a check failed counts it under the name of the check`() {
    readiness.evaluate()
    readiness.evaluate()

    assertEquals(2, failures("runtime"))
    assertEquals(0, failures("shutdown"))
  }

  @Test
  fun `a check that throws is failed and the others are still asked`() {
    val broken =
        object : ReadinessCheck {
          override val name = "broken"

          override fun check(): CheckResult = throw IllegalStateException("boom")
        }
    val report =
        Readiness(
                listOf(broken, ShutdownCheck(lifecycle)),
                lifecycle,
                HealthTelemetry(otel),
            )
            .evaluate()

    assertEquals(mapOf("broken" to CheckState.FAILED, "shutdown" to CheckState.OK), report.checks)
    assertEquals(1, failures("broken"))
  }
}
