package dev.lawlan.runline.engine

import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
import io.opentelemetry.semconv.ServiceAttributes
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The Engine's OpenTelemetry: traces, metrics and logs are exported as the standard OpenTelemetry
 * settings of the deployment say (07 "可觀測性"). The Engine closes it as it stops ([shutdownWithin]);
 * no JVM shutdown hook is registered, so starting and stopping the Engine in one JVM leaves none
 * behind.
 */
fun getOpenTelemetry(serviceName: String): OpenTelemetrySdk =
    AutoConfiguredOpenTelemetrySdk.builder()
        .addResourceCustomizer { oldResource, _ ->
          oldResource
              .toBuilder()
              .putAll(oldResource.attributes)
              .put(ServiceAttributes.SERVICE_NAME, serviceName)
              .build()
        }
        .disableShutdownHook()
        .build()
        .openTelemetrySdk

/**
 * Sends what has not been sent yet and stops every exporter, waiting at most [limit] (the grace
 * time of a shutdown). The SDK's own shutdown blocks for as long as its readers and exporters take
 * (seconds, more with a collector that does not answer), so it runs on a thread of its own; past
 * [limit] the Engine goes on stopping and that thread ends when the SDK gives up by itself.
 */
fun OpenTelemetrySdk.shutdownWithin(limit: Duration) {
  val closing =
      thread(name = "opentelemetry-shutdown", isDaemon = true) {
        shutdown().join(SDK_SHUTDOWN_LIMIT.toMillis(), TimeUnit.MILLISECONDS)
      }
  closing.join(limit)
}

/** What [OpenTelemetrySdk.close] itself allows its shutdown. */
private val SDK_SHUTDOWN_LIMIT = Duration.ofSeconds(10)
