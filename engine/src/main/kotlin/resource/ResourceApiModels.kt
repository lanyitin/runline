package dev.lawlan.runline.engine.resource

import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** [type] is a counter when left out; [settings] and [secretAlias] belong to the type (ADR-019). */
@Serializable
data class CreateResourceRequest(
    val name: String,
    val capacity: Int,
    val type: String? = null,
    val settings: JsonObject? = null,
    val secretAlias: String? = null,
)

/**
 * [capacity] and [enabled] are optional but at least one must be given. [name] and [type] cannot be
 * changed: they are read only to refuse a request that tries.
 */
@Serializable
data class UpdateResourceRequest(
    val capacity: Int? = null,
    val enabled: Boolean? = null,
    val name: String? = null,
    val type: String? = null,
    val settings: JsonObject? = null,
    val secretAlias: String? = null,
)

/** The answer to a refused definition or change: [problem] is the category of the reason. */
@Serializable
data class InvalidResourceResponse(
    val error: String,
    val message: String,
    val problem: String,
)

/** What a check says: [failure] only when it did not pass; no reason, no path, no address. */
@Serializable
data class CheckDoc(val ok: Boolean, val failure: String? = null, val checkedAt: String)

fun CheckResult.toDoc() = CheckDoc(ok, failure?.wire, checkedAt.toString())

/**
 * What a type says about its use now: the requests an `openai-compatible` resource has in flight.
 */
@Serializable data class UsageDoc(val inFlightRequests: Int)

@Serializable
data class HolderDoc(
    val runId: String,
    val pipeline: String,
    val heldSince: String,
    val heldSeconds: Double,
)

@Serializable
data class WaiterDoc(
    val runId: String,
    val pipeline: String,
    val waitingFor: List<String>,
    val waitingSince: String,
    val waitedSeconds: Double,
)

@Serializable
data class DeclaringDefinitionDoc(
    val contentHash: String,
    val pipeline: String,
    val declaredType: String?,
    val triggers: Int,
)

/** The pipeline definitions that declare a resource; [triggers] are bound to those definitions. */
@Serializable
data class DeclaredByDoc(
    val count: Int,
    val triggers: Int,
    val definitions: List<DeclaringDefinitionDoc>,
)

/**
 * A resource with who holds it and who waits for it, and who declares it; waiters are in the order
 * they are served.
 */
@Serializable
data class ResourceResponse(
    val name: String,
    val type: String,
    val capacity: Int,
    val enabled: Boolean,
    val settings: JsonObject,
    val secretAlias: String?,
    /**
     * What the alias comes to against the keystore: `not_set`, `found`, `missing` or
     * `invalid_secret`. Never the secret.
     */
    val secretStatus: String,
    /**
     * The most requests the entity can have at once (capacity times what each holder may do at
     * once), for the types that can say; null for the others.
     */
    val concurrencyLimit: Int?,
    /** The use of the entity now, for the types that can say; null for the others. */
    val usage: UsageDoc?,
    /** The last check of the entity; null when never checked or when the settings changed since. */
    val lastCheck: CheckDoc?,
    val createdBy: String,
    val createdAt: String,
    val updatedBy: String,
    val updatedAt: String,
    val holders: List<HolderDoc>,
    val waiters: List<WaiterDoc>,
    val declaredBy: DeclaredByDoc,
)

/** The answer to a delete that is refused because runs hold the resource or wait for it. */
@Serializable
data class ResourceInUseResponse(
    val error: String,
    val message: String,
    val holders: Int,
    val waiters: Int,
)

/** What deleting a resource would touch (`preview=true`); nothing has been changed. */
@Serializable
data class RemovalPreviewResponse(
    val resource: String,
    val definitions: Int,
    val triggers: Int,
    val holders: Int,
    val waiters: Int,
    val inUse: Boolean,
)

@Serializable data class ResourceListResponse(val resources: List<ResourceResponse>)

@Serializable
data class ForceReleaseResponse(
    val resource: String,
    val runId: String,
    val pipeline: String,
    val heldSince: String,
)

@Serializable data class ResourceProblemDoc(val resource: String, val problem: String)

/** Refusal to create a run because of the shared resources its pipeline declares. */
@Serializable
data class ResourcesUnavailableResponse(
    val error: String,
    val message: String,
    val problems: List<ResourceProblemDoc>,
)

fun ResourceView.toResponse(clock: Clock): ResourceResponse {
  val now = clock.instant()
  return ResourceResponse(
      name = resource.name,
      type = resource.type.wireName,
      capacity = resource.capacity,
      enabled = resource.enabled,
      settings = resource.settings,
      secretAlias = resource.secretAlias,
      secretStatus = aliasState.wire,
      concurrencyLimit = concurrencyLimit,
      usage = usage?.let { UsageDoc(it.inFlightRequests) },
      lastCheck = resource.lastCheck?.toDoc(),
      createdBy = resource.createdBy,
      createdAt = resource.createdAt.toString(),
      updatedBy = resource.updatedBy,
      updatedAt = resource.updatedAt.toString(),
      holders =
          activity.holders.map {
            HolderDoc(
                it.runId.toString(),
                it.pipelineName,
                it.since.toString(),
                seconds(it.since, now),
            )
          },
      waiters =
          activity.waiters.map {
            WaiterDoc(
                it.runId.toString(),
                it.pipelineName,
                it.waitingFor,
                it.since.toString(),
                seconds(it.since, now),
            )
          },
      declaredBy =
          DeclaredByDoc(
              declarations.definitions.size,
              declarations.triggerCount,
              declarations.definitions.map {
                DeclaringDefinitionDoc(it.contentHash, it.pipeline, it.declaredType, it.triggers)
              },
          ),
  )
}

private fun seconds(since: Instant, now: Instant) =
    Duration.between(since, now).toMillis().coerceAtLeast(0) / 1000.0
