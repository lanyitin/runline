package dev.lawlan.runline.engine.support

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable

/**
 * A real OpenTelemetry Collector that accepts OTLP over HTTP and writes everything it receives, in
 * full, to its own output (the debug exporter); [metrics] reads what arrived from there. The Engine
 * is pointed at [endpoint] with the standard OpenTelemetry settings, as a deployment does.
 */
class OtelCollector : AutoCloseable {
  private val container =
      GenericContainer(IMAGE).apply {
        withExposedPorts(OTLP_HTTP)
        withCopyToContainer(Transferable.of(CONFIG), "/etc/otelcol/config.yaml")
        waitingFor(Wait.forLogMessage(".*Everything is ready.*", 1))
        withStartupTimeout(TestTimeouts.container)
        start()
      }

  /** Where OTLP over HTTP is sent, as `otel.exporter.otlp.endpoint` takes it. */
  val endpoint: String
    get() = "http://${container.host}:${container.getMappedPort(OTLP_HTTP)}"

  /**
   * Every metric the collector has received so far, one entry per metric in each export: its name
   * and the attributes of its data points as the collector printed them (`key: Str(value)`).
   */
  fun metrics(): List<ReceivedMetric> {
    val metrics = mutableListOf<ReceivedMetric>()
    // The lines under "Metric #n" until the next heading of any kind belong to that metric.
    var current: ReceivedMetric? = null
    for (line in container.logs.lines()) {
      val text = line.trim()
      when {
        text.startsWith("Metric #") -> current = null.also { metrics.add(ReceivedMetric()) }
        HEADINGS.any { text.startsWith(it) } -> current = null
        current == null && metrics.isNotEmpty() && metrics.last().name.isEmpty() ->
            if (text.startsWith(NAME))
                current = metrics.last().also { it.name = text.removePrefix(NAME) }
        text.startsWith("-> ") -> current?.attributes?.add(text.removePrefix("-> "))
      }
    }
    return metrics.filter { it.name.isNotEmpty() }
  }

  /** The collector's own output, for the message of a failed wait. */
  val logs: String
    get() = container.logs

  override fun close() = container.stop()

  /** A metric as it arrived; [attributes] holds every `-> key: value` line printed under it. */
  class ReceivedMetric {
    var name: String = ""
      internal set

    val attributes: MutableList<String> = mutableListOf()

    override fun toString() = "$name $attributes"
  }

  private companion object {
    /** The core distribution, from the mirror the other test images come from. */
    const val IMAGE = "mirror.gcr.io/otel/opentelemetry-collector:0.115.0"
    const val OTLP_HTTP = 4318
    const val NAME = "-> Name: "
    /** Where the debug exporter begins something other than the metric before. */
    val HEADINGS = listOf("ResourceMetrics #", "ScopeMetrics #", "ResourceSpans #", "ResourceLog #")
    val CONFIG =
        """
        receivers:
          otlp:
            protocols:
              http:
                endpoint: 0.0.0.0:$OTLP_HTTP
        exporters:
          debug:
            verbosity: detailed
        service:
          pipelines:
            metrics:
              receivers: [otlp]
              exporters: [debug]
            traces:
              receivers: [otlp]
              exporters: [debug]
            logs:
              receivers: [otlp]
              exporters: [debug]
        """
            .trimIndent()
  }
}
