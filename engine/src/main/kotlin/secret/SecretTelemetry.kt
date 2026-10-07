package dev.lawlan.runline.engine.secret

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes

/**
 * Metrics of the secret store (ADR-019 decision 10): how many reloads there were and how they
 * ended. Labelled by the result only, never by an alias or a value.
 */
class SecretTelemetry(openTelemetry: OpenTelemetry) {
  private val reloads =
      openTelemetry
          .getMeter("runline.secrets")
          .counterBuilder("runline.secrets.reloads")
          .setDescription("Reloads of the keystore, by result: ok or the category of the failure")
          .build()

  fun reloaded(result: String) = reloads.add(1, Attributes.of(RESULT, result))

  private companion object {
    val RESULT: AttributeKey<String> = AttributeKey.stringKey("result")
  }
}
