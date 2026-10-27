package dev.lawlan.runline.engine

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
import io.opentelemetry.semconv.ServiceAttributes

private const val METRICS_EXPORTER = "otel.metrics.exporter"

/**
 * The Engine's own telemetry, configured by OpenTelemetry's standard settings (environment
 * variables such as `OTEL_METRICS_EXPORTER`, `OTEL_EXPORTER_OTLP_ENDPOINT`). The one default the
 * Engine adds is that metrics are not exported unless the deployer chooses an exporter: a
 * deployment without a collector then does not wait for one at shutdown.
 */
fun getOpenTelemetry(serviceName: String): OpenTelemetry {
  return AutoConfiguredOpenTelemetrySdk.builder()
      .addPropertiesCustomizer { config ->
        if (config.getString(METRICS_EXPORTER) == null) mapOf(METRICS_EXPORTER to "none")
        else emptyMap()
      }
      .addResourceCustomizer { oldResource, _ ->
        oldResource
            .toBuilder()
            .putAll(oldResource.attributes)
            .put(ServiceAttributes.SERVICE_NAME, serviceName)
            .build()
      }
      .build()
      .openTelemetrySdk
}
