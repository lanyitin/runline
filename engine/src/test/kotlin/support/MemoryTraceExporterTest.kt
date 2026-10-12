package dev.lawlan.runline.engine.support

import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
import kotlin.test.Test
import kotlin.test.assertEquals

class MemoryTraceExporterTest {
  private fun oneSpanInARecording(): List<String> = MemoryTraceExporter.recording {
    val sdk = AutoConfiguredOpenTelemetrySdk.builder().build().openTelemetrySdk
    sdk.getTracer("test").spanBuilder("a-span").startSpan().end()
    sdk.sdkTracerProvider.forceFlush().join(10, java.util.concurrent.TimeUnit.SECONDS)
    val names = MemoryTraceExporter.spans.finishedSpanItems.map { it.name }
    // An Engine that ends shuts its SDK down, and its exporter with it.
    sdk.close()
    names
  }

  @Test
  fun `every recording sees its own spans, also after an earlier one ended its exporter`() {
    assertEquals(listOf("a-span"), oneSpanInARecording())
    assertEquals(listOf("a-span"), oneSpanInARecording())
  }
}
