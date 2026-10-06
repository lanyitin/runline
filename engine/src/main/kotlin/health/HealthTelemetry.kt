package dev.lawlan.runline.engine.health

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes

/** Metrics of the probes: the checks that found something wrong, by check name (ADR-018). */
class HealthTelemetry(openTelemetry: OpenTelemetry) {
  private val failures =
      openTelemetry
          .getMeter("runline.health")
          .counterBuilder("runline.health.check.failures")
          .build()

  fun failed(check: String) {
    failures.add(1, Attributes.of(CHECK, check))
  }

  private companion object {
    val CHECK = AttributeKey.stringKey("check")
  }
}
