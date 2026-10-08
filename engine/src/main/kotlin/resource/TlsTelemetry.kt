package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.accessors.tls.TlsFailure
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import org.slf4j.LoggerFactory

/**
 * What the Engine records of the failures of TLS of resources' connections (WI-52): a count by
 * category, labelled by the resource's name, its type and the category only, and a log line with
 * the same. Nothing of a certificate or a key is in either.
 */
class TlsTelemetry(openTelemetry: OpenTelemetry) {
  private val log = LoggerFactory.getLogger(TlsTelemetry::class.java)
  private val failures =
      openTelemetry
          .getMeter("runline.resources.tls")
          .counterBuilder("runline.resources.tls.failures")
          .setDescription("Connections of resources that failed in TLS, by category")
          .build()

  fun failed(resource: String, type: String, failure: TlsFailure) {
    failures.add(
        1,
        Attributes.of(RESOURCE, resource, TYPE, type, KIND, failure.wire),
    )
    log.warn(
        "Shared resource {} ({}): a connection failed in TLS: {}",
        resource,
        type,
        failure.wire,
    )
  }

  private companion object {
    val RESOURCE: AttributeKey<String> = AttributeKey.stringKey("resource")
    val TYPE: AttributeKey<String> = AttributeKey.stringKey("type")
    val KIND: AttributeKey<String> = AttributeKey.stringKey("kind")
  }
}
