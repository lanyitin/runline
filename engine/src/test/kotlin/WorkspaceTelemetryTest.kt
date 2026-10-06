package dev.lawlan.runline.engine

import ch.qos.logback.classic.Logger
import dev.lawlan.runline.core.FileScope
import dev.lawlan.runline.engine.support.SnapshotListAppender
import dev.lawlan.runline.runner.WorkspaceEvent
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import kotlin.test.*
import org.slf4j.LoggerFactory

class WorkspaceTelemetryTest {
  private val reader = InMemoryMetricReader.create()
  private val otel =
      OpenTelemetrySdk.builder()
          .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(reader).build())
          .build()
  private val telemetry = WorkspaceTelemetry(otel)
  private val logs = SnapshotListAppender()
  private val logger = LoggerFactory.getLogger(WorkspaceTelemetry::class.java) as Logger

  @BeforeTest
  fun attach() {
    logs.start()
    logger.addAppender(logs)
  }

  @AfterTest
  fun detach() {
    logger.detachAppender(logs)
  }

  private fun metric(name: String) = reader.collectAllMetrics().first { it.name == name }

  @Test
  fun `counts created and removed directories by scope and logs them`() {
    telemetry.onEvent(WorkspaceEvent.Created(FileScope.PIPELINE_SHARED, "demo", null))
    telemetry.onEvent(WorkspaceEvent.Created(FileScope.RUN_PRIVATE, "demo", "run-1"))
    telemetry.onEvent(WorkspaceEvent.Removed(FileScope.RUN_PRIVATE, "demo", "run-1"))

    val created = metric("runline.workspace.directories.created").longSumData.points
    assertEquals(
        mapOf("PIPELINE_SHARED" to 1L, "RUN_PRIVATE" to 1L),
        created.associate { it.attributes.get(AttributeKey.stringKey("scope"))!! to it.value },
    )
    val removed = metric("runline.workspace.directories.removed").longSumData.points
    assertEquals(1L, removed.single().value)

    val messages = logs.snapshot().map { it.formattedMessage }
    assertEquals(3, messages.size)
    assertTrue(messages[1].contains("demo") && messages[1].contains("run-1"), messages[1])
  }

  @Test
  fun `records shared usage per pipeline`() {
    telemetry.onEvent(WorkspaceEvent.SharedUsage("demo", 42))
    telemetry.onEvent(WorkspaceEvent.SharedUsage("demo", 7))

    val point = metric("runline.workspace.shared.usage.bytes").longGaugeData.points.single()
    assertEquals(7L, point.value)
    assertEquals("demo", point.attributes.get(AttributeKey.stringKey("pipeline")))
    assertTrue(logs.snapshot().any { it.formattedMessage.contains("42") })
  }
}
