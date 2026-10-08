package dev.lawlan.runline.engine.secret

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes

/**
 * Metrics of the secret store (ADR-019 decision 10): how many reloads there were and how they
 * ended. Labelled by the result only, never by an alias or a value.
 */
class SecretTelemetry(openTelemetry: OpenTelemetry) {
  private val meter = openTelemetry.getMeter("runline.secrets")
  private val reloads =
      meter
          .counterBuilder("runline.secrets.reloads")
          .setDescription("Reloads of the keystore, by result: ok or the category of the failure")
          .build()

  fun reloaded(result: String) = reloads.add(1, Attributes.of(RESULT, result))

  /**
   * The whole days each certificate alias has left, as [daysLeft] says when the metric is read
   * (WI-52): the earliest end of validity of the entry's certificates. Labelled by the alias only.
   */
  fun watchCertificates(daysLeft: () -> Map<String, Long>) {
    meter
        .gaugeBuilder("runline.secrets.certificate.days_left")
        .ofLongs()
        .setUnit("d")
        .setDescription(
            "Whole days a certificate alias of the keystore has left; negative: expired"
        )
        .buildWithCallback { measurement ->
          daysLeft().forEach { (alias, days) ->
            measurement.record(days, Attributes.of(ALIAS, alias))
          }
        }
  }

  private companion object {
    val RESULT: AttributeKey<String> = AttributeKey.stringKey("result")
    val ALIAS: AttributeKey<String> = AttributeKey.stringKey("alias")
  }
}
