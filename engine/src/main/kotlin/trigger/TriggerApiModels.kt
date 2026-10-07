package dev.lawlan.runline.engine.trigger

import kotlinx.serialization.Serializable

@Serializable
data class CreateTriggerRequest(
    val name: String,
    /** `cron` or `webhook`. */
    val kind: String,
    val contentHash: String,
    /** Whose version of the content, when several uploaders have it (ADR-020). */
    val uploader: String? = null,
    val pipeline: String,
    val parameters: Map<String, String> = emptyMap(),
    /** Cron triggers: a standard five-field expression. */
    val cron: String? = null,
    /** Cron triggers: an IANA time zone; UTC when left out. */
    val timeZone: String? = null,
    val enabled: Boolean = true,
)

/** Every field is optional; at least one must be given. */
@Serializable
data class UpdateTriggerRequest(
    val contentHash: String? = null,
    /** Whose version to move to (with [contentHash]) or to use of the current content. */
    val uploader: String? = null,
    val pipeline: String? = null,
    val parameters: Map<String, String>? = null,
    val enabled: Boolean? = null,
    val cron: String? = null,
    val timeZone: String? = null,
)

/**
 * A trigger as an administrator sees it. A webhook's secret is not here, in any form: only that one
 * is set and when it last changed.
 */
@Serializable
data class TriggerResponse(
    val name: String,
    val kind: String,
    val contentHash: String,
    /** Whose version of the content the trigger is bound to. */
    val uploader: String,
    val pipeline: String,
    /** The parameters as the administrator gave them. */
    val parameters: Map<String, String>,
    /** What every run gets: those, plus the defaults of the bound version. */
    val effectiveParameters: Map<String, String>,
    val enabled: Boolean,
    val cron: String?,
    val timeZone: String?,
    /** Webhook triggers: the path to call, with the headers in the README. */
    val webhookPath: String?,
    val secretConfigured: Boolean,
    val secretRotatedAt: String?,
    val createdBy: String,
    val createdAt: String,
    val updatedBy: String,
    val updatedAt: String,
)

@Serializable data class TriggerListResponse(val triggers: List<TriggerResponse>)

/** The response to creating a webhook trigger or rotating its secret: the only time it is shown. */
@Serializable data class TriggerSecretResponse(val trigger: TriggerResponse, val secret: String?)

@Serializable
data class FiringResponse(
    val firedAt: String,
    val scheduledFor: String?,
    val deliveryId: String?,
    /** `run_created`, `refused`, `failed`, `interrupted` or `pending`. */
    val outcome: String,
    val reason: String?,
    val detail: String?,
    val runId: String?,
)

@Serializable data class FiringListResponse(val firings: List<FiringResponse>)

/** A refused request to create or change a trigger, and which part of it is wrong. */
@Serializable
data class InvalidTriggerResponse(val error: String, val problem: String, val message: String)

@Serializable data class WebhookAccepted(val status: String = "accepted")

fun TriggerView.toResponse(): TriggerResponse =
    TriggerResponse(
        name = trigger.name,
        kind = trigger.kind.name.lowercase(),
        contentHash = trigger.contentHash,
        uploader = trigger.uploader,
        pipeline = trigger.pipeline,
        parameters = trigger.parameters,
        effectiveParameters = effectiveParameters,
        enabled = trigger.enabled,
        cron = trigger.cronExpression,
        timeZone = trigger.timeZone,
        webhookPath =
            "/api/v1/webhooks/${trigger.name}".takeIf { trigger.kind == TriggerKind.WEBHOOK },
        secretConfigured = trigger.secretRotatedAt != null,
        secretRotatedAt = trigger.secretRotatedAt?.toString(),
        createdBy = trigger.createdBy,
        createdAt = trigger.createdAt.toString(),
        updatedBy = trigger.updatedBy,
        updatedAt = trigger.updatedAt.toString(),
    )

fun Firing.toResponse() =
    FiringResponse(
        firedAt = firedAt.toString(),
        scheduledFor = scheduledFor?.toString(),
        deliveryId = deliveryId,
        outcome = outcome.name.lowercase(),
        reason = reason,
        detail = detail,
        runId = runId?.toString(),
    )
