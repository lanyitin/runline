package dev.lawlan.runline.engine.trigger

import java.time.Instant
import java.util.UUID

enum class TriggerKind {
  CRON,
  WEBHOOK,
}

/**
 * A trigger as an administrator sees it. Nothing of a webhook's secret is in here, not even its
 * hash: [secretRotatedAt] only says that one is set and when it last changed.
 */
data class Trigger(
    val id: Long,
    val name: String,
    val kind: TriggerKind,
    /** The definition the trigger is bound to, which is one version of a pipeline. */
    val definitionId: Long,
    val contentHash: String,
    val pipeline: String,
    /** The fixed parameters of every run it creates, defaults applied. */
    val parameters: Map<String, String>,
    val enabled: Boolean,
    val cronExpression: String?,
    val timeZone: String?,
    val secretRotatedAt: Instant?,
    val createdBy: String,
    val createdAt: Instant,
    val updatedBy: String,
    val updatedAt: Instant,
)

/** A trigger to be stored. A webhook has [secretHash]; a cron has [cronExpression] and zone. */
data class NewTrigger(
    val name: String,
    val kind: TriggerKind,
    val definitionId: Long,
    val parameters: Map<String, String>,
    val enabled: Boolean,
    val cronExpression: String?,
    val timeZone: String?,
    val secretHash: String?,
    val by: String,
    val at: Instant,
)

/** The whole new state of a trigger after a change; its kind and secret never change here. */
data class TriggerChange(
    val definitionId: Long,
    val parameters: Map<String, String>,
    val enabled: Boolean,
    val cronExpression: String?,
    val timeZone: String?,
)

/** What checking a webhook call needs: the stored hash of the secret, never the secret. */
data class WebhookCredential(val triggerId: Long, val enabled: Boolean, val secretHash: String)

enum class FiringOutcome {
  /** Claimed and in progress; a process that ended meanwhile leaves it, see [INTERRUPTED]. */
  PENDING,
  RUN_CREATED,
  REFUSED,
  /** An unexpected error stopped the run from being created. */
  FAILED,
  /** The Engine ended before the firing was settled; whether a run was created is not known. */
  INTERRUPTED,
}

/** One time a trigger went off. */
data class Firing(
    val id: Long,
    val firedAt: Instant,
    /** Cron: the scheduled time that fired. */
    val scheduledFor: Instant?,
    /** Webhook: the caller's delivery identifier. */
    val deliveryId: String?,
    val outcome: FiringOutcome,
    /** Stable code of why a firing was refused or failed. */
    val reason: String?,
    val detail: String?,
    val runId: UUID?,
)
