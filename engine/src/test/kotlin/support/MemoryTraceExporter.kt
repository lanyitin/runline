package dev.lawlan.runline.engine.support

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties
import io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSpanExporterProvider
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.export.SpanExporter

/**
 * The span exporter of OpenTelemetry's own in-memory exporter, chosen the way an operator chooses
 * an exporter: `OTEL_TRACES_EXPORTER=runline-memory`. With it the Engine that a test starts is the
 * real one, with its real OpenTelemetry set-up, and the spans it records can be read.
 */
class MemoryTraceExporter : ConfigurableSpanExporterProvider {
  override fun getName() = NAME

  override fun createExporter(config: ConfigProperties): SpanExporter = spans

  companion object {
    const val NAME = "runline-memory"

    /**
     * Everything exported since the current [recording] began. An Engine that ends shuts its
     * exporter down, and a shut down exporter takes nothing more, so each recording has a new one.
     */
    @Volatile
    var spans: InMemorySpanExporter = InMemorySpanExporter.create()
      private set

    /**
     * Runs [block] with the Engine's traces going to [spans], and restores what was set. The
     * exporter is chosen by a system property, which is global: tests that use this must not run
     * beside tests of the same process that read it (the tests of one JVM run one after another).
     */
    fun <T> recording(block: () -> T): T {
      val key = "otel.traces.exporter"
      val before = System.getProperty(key)
      System.setProperty(key, NAME)
      spans = InMemorySpanExporter.create()
      try {
        return block()
      } finally {
        if (before == null) System.clearProperty(key) else System.setProperty(key, before)
      }
    }
  }
}
