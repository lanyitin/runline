package dev.lawlan.runline.engine.trigger

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes

/** Why a webhook call was turned away before it became a firing. */
enum class WebhookRejection(val reason: String, val cause: String) {
  /** No secret was given. */
  NO_SECRET("unauthorized", "no_secret"),
  UNKNOWN_TRIGGER("unauthorized", "unknown_trigger"),
  DISABLED("unauthorized", "disabled"),
  WRONG_SECRET("unauthorized", "wrong_secret"),
  NO_DELIVERY_ID("invalid_delivery_id", "missing"),
  BAD_DELIVERY_ID("invalid_delivery_id", "malformed"),
}

/**
 * Metrics of triggers (07-nfr-risks): firings by trigger and result, deliveries recognised as
 * repeats, and webhook calls turned away. A rejected call carries only the rejection, never the
 * name from the request, because anybody can send any name and a metric with that label would grow
 * without limit.
 */
class TriggerTelemetry(openTelemetry: OpenTelemetry) {
  private val meter = openTelemetry.getMeter("runline.triggers")
  private val firings = meter.counterBuilder("runline.triggers.firings").build()
  private val refusals = meter.counterBuilder("runline.triggers.refused").build()
  private val failures = meter.counterBuilder("runline.triggers.failed").build()
  private val duplicates = meter.counterBuilder("runline.triggers.webhook.duplicates").build()
  private val rejections = meter.counterBuilder("runline.triggers.webhook.rejected").build()

  fun created(trigger: Trigger) {
    firings.add(
        1,
        Attributes.of(KIND, trigger.kind.name, TRIGGER, trigger.name, OUTCOME, "created"),
    )
  }

  fun refused(trigger: Trigger, reason: String) {
    refusals.add(1, Attributes.of(KIND, trigger.kind.name, TRIGGER, trigger.name, REASON, reason))
  }

  fun failed(trigger: Trigger) {
    failures.add(1, Attributes.of(KIND, trigger.kind.name, TRIGGER, trigger.name))
  }

  fun duplicate(trigger: Trigger) {
    duplicates.add(1, Attributes.of(TRIGGER, trigger.name))
  }

  fun rejected(rejection: WebhookRejection) {
    rejections.add(1, Attributes.of(REASON, rejection.reason, CAUSE, rejection.cause))
  }

  private companion object {
    val KIND = AttributeKey.stringKey("kind")
    val TRIGGER = AttributeKey.stringKey("trigger")
    val OUTCOME = AttributeKey.stringKey("outcome")
    val REASON = AttributeKey.stringKey("reason")
    val CAUSE = AttributeKey.stringKey("cause")
  }
}
