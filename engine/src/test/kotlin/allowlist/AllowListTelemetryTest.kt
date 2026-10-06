package dev.lawlan.runline.engine.allowlist

import dev.lawlan.runline.analyzer.AllowListEntry
import dev.lawlan.runline.engine.support.AllowListRig
import dev.lawlan.runline.engine.support.CapturedLogs
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlin.test.*

/** What a change of the allow list leaves in the log, in traces and in metrics (WI-10). */
class AllowListTelemetryTest {
  private val reader = InMemoryMetricReader.create()
  private val spans = InMemorySpanExporter.create()
  private val otel =
      OpenTelemetrySdk.builder()
          .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(reader).build())
          .setTracerProvider(
              SdkTracerProvider.builder()
                  .addSpanProcessor(SimpleSpanProcessor.create(spans))
                  .build()
          )
          .build()
  private val rig = AllowListRig(listOf(AllowListEntry("java.lang")), otel)
  private val logs = CapturedLogs()

  @AfterTest fun close() = logs.close()

  private fun metric(name: String): MetricData? =
      reader.collectAllMetrics().firstOrNull { it.name == name }

  private fun counter(name: String, vararg labels: Pair<String, String>): Long =
      metric(name)
          ?.longSumData
          ?.points
          ?.filter { p ->
            labels.all { (k, v) -> p.attributes.get(AttributeKey.stringKey(k)) == v }
          }
          ?.sumOf { it.value } ?: 0

  @Test
  fun `the number of verdicts a change flips is counted by direction`() {
    rig.uploadNeedingUtil("one")
    rig.uploadNeedingUtil("two")
    rig.add("java.util")
    rig.uploadNeedingUtil("three")
    rig.remove("java.util")

    assertEquals(2, counter("runline.allowlist.verdict.changes", "direction" to "to_safe"))
    assertEquals(3, counter("runline.allowlist.verdict.changes", "direction" to "to_unsafe"))
  }

  @Test
  fun `a preview changes no verdicts, so it counts none`() {
    rig.uploadNeedingUtil("one")

    rig.admin.change(
        AllowListChange.Add(EntryKind.PACKAGE, "java.util"),
        rig.root,
        ChangeMode.PREVIEW,
    )

    assertEquals(0, counter("runline.allowlist.verdict.changes", "direction" to "to_safe"))
  }

  @Test
  fun `each operation is counted by kind and by whether it was a preview`() {
    rig.add("java.util")
    rig.admin.change(
        AllowListChange.Remove(EntryKind.PACKAGE, "java.util"),
        rig.root,
        ChangeMode.PREVIEW,
    )
    rig.admin.change(AllowListChange.Recheck, rig.root)

    assertEquals(
        1,
        counter("runline.allowlist.operations", "operation" to "add", "preview" to "false"),
    )
    assertEquals(
        1,
        counter("runline.allowlist.operations", "operation" to "remove", "preview" to "true"),
    )
    assertEquals(
        1,
        counter("runline.allowlist.operations", "operation" to "recheck", "preview" to "false"),
    )
  }

  @Test
  fun `a change is traced with its operation and the name of whoever made it`() {
    rig.admin.change(AllowListChange.Add(EntryKind.PACKAGE, "java.util"), rig.ann)

    val span = spans.finishedSpanItems.single { it.name == "runline.allowlist.change" }
    assertEquals("add", span.attributes.get(AttributeKey.stringKey("runline.allowlist.operation")))
    assertEquals("ann", span.attributes.get(AttributeKey.stringKey("runline.actor")))
    assertEquals(false, span.attributes.get(AttributeKey.booleanKey("runline.allowlist.preview")))
  }

  @Test
  fun `a failing change marks its span as an error`() {
    val broken =
        AllowListAdmin(
            rig.store,
            dev.lawlan.runline.analyzer.SafetyAnalyzer(),
            rig.clock,
            rig.telemetry,
            rig.dir.resolve("missing-scratch-directory"),
        )
    rig.uploadNeedingUtil("one")

    assertFails { broken.change(AllowListChange.Add(EntryKind.PACKAGE, "java.util"), rig.root) }

    val span = spans.finishedSpanItems.single { it.name == "runline.allowlist.change" }
    assertEquals(io.opentelemetry.api.trace.StatusCode.ERROR, span.status.statusCode)
  }

  @Test
  fun `a change is logged with who made it and what it did`() {
    rig.uploadNeedingUtil("one")

    rig.admin.change(AllowListChange.Add(EntryKind.PACKAGE, "java.util"), rig.ann)

    val line =
        logs.at(ch.qos.logback.classic.Level.INFO).single { "java.util" in it && "ann" in it }
    assertTrue("add" in line, line)
    assertTrue("1 became safe" in line, line)
  }
}
