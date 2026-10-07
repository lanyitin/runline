package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.engine.auth.ApiIdentity
import dev.lawlan.runline.engine.auth.Role
import dev.lawlan.runline.engine.run.RunState
import dev.lawlan.runline.engine.support.CapturedLogs
import dev.lawlan.runline.engine.support.RunHarness
import dev.lawlan.runline.engine.support.RunHarness.Companion.usingTyped
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import java.nio.file.Files
import java.time.Duration
import kotlin.test.*

/**
 * What the Engine leaves behind of the operations on accessors (ADR-019): a span of the run's trace
 * per operation, the run's log, and the Engine's log. Nothing of it holds a path or file content.
 */
class ResourceAccessorObservabilityTest {
  private val spans = InMemorySpanExporter.create()
  private val logs = CapturedLogs()
  private val otel =
      OpenTelemetrySdk.builder()
          .setTracerProvider(
              SdkTracerProvider.builder()
                  .addSpanProcessor(SimpleSpanProcessor.create(spans))
                  .build()
          )
          .build()
  private val h =
      RunHarness(
          maxConcurrent = 2,
          resourceWaitTimeout = Duration.ofHours(1),
          openTelemetry = otel,
      )

  @AfterTest
  fun close() {
    h.close()
    logs.close()
  }

  private val secret = "secret-content-1234"

  private fun attribute(span: io.opentelemetry.sdk.trace.data.SpanData, key: String): String? =
      span.attributes.get(
          io.opentelemetry.api.common.AttributeKey.stringKey("runline.resource.$key")
      )

  private fun runLog(id: java.util.UUID) = h.runStore.read(id, 0, 1000).map { it.line }

  private fun ran(): java.util.UUID {
    h.defineFile("log", "logs/out.txt")
    Files.createDirectories(h.resourceRoot.resolve("broken-dir"))
    h.defineFile("broken", "broken-dir")
    val hash =
        h.upload(
            "observed",
            """
            context.getAccessors().file("log").writeText("$secret");
            String seen = "";
            try { context.getAccessors().file("broken").readText(); }
            catch (ResourceAccessException e) { seen = e.getMessage() + "|" + e.getErrorId(); }
            context.getFiles().writeText(FileScope.PIPELINE_SHARED, "seen", seen);
            """
                .trimIndent(),
            declaration = usingTyped("log" to "file", "broken" to "file"),
        )
    val id = h.start(hash, "observed")
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(id).state)
    return id
  }

  @Test
  fun `every operation is a span of the run's trace with name, type and operation and nothing else`() {
    val id = ran()

    val root = spans.finishedSpanItems.single { it.name == "runline.run" }
    val operations = spans.finishedSpanItems.filter { it.name.startsWith("runline.resource.") }
    assertEquals(
        listOf("file.read", "file.write"),
        operations.map { attribute(it, "operation")!! }.sorted(),
    )
    operations.forEach {
      assertEquals(root.spanId, it.parentSpanId)
      assertEquals("file", attribute(it, "type"))
      val text = it.attributes.toString() + it.name
      assertFalse(text.contains(secret) || text.contains(h.resourceRoot.toString()), text)
    }
    assertNotNull(id)
  }

  @Test
  fun `the run's log records the acquisition and the error category, never a path or content`() {
    val id = ran()

    val lines = runLog(id).joinToString("\n")
    assertTrue(lines.contains("log") && lines.contains("file"), lines)
    assertTrue(lines.contains("FAILED"), "the error category of the failed read: $lines")
    assertFalse(lines.contains(secret) || lines.contains(h.resourceRoot.toString()), lines)
  }

  @Test
  fun `the run is told the category and an errorId, the Engine's log has the original under that id`() {
    ran()

    val seen = Files.readString(h.shared("observed", "seen"))
    assertFalse(seen.contains(h.resourceRoot.toString()), seen)
    val errorId = seen.substringAfterLast('|')
    assertTrue(errorId.isNotBlank() && errorId != "null", seen)
    val event = logs.lines.single { it.startsWith("WARN") && it.contains(errorId) }
    assertTrue(event.contains("broken"), event)
    assertTrue(event.contains("IOException"), "the original exception is in the log: $event")
  }

  @Test
  fun `a forced release is in the run's log with its reason and in the Engine's log with the administrator`() {
    h.defineFile("log", "out.txt")
    val hash =
        h.upload(
            "held",
            RunHarness.holdUntilReleased("started"),
            declaration = usingTyped("log" to "file"),
        )
    val id = h.start(hash, "held")
    h.awaitFile(h.shared("held", "started"))

    h.coordinator!!.forceRelease("log", id, ApiIdentity("ops", Role.ADMIN))
    Files.writeString(h.shared("held", "release"), "x")
    h.awaitEnd(id)

    val lines = runLog(id).joinToString("\n")
    assertTrue(lines.contains("強制釋放"), lines)
    assertTrue(logs.lines.any { it.contains("released by force") && it.contains("ops") })
  }
}
