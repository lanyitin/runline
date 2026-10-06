package dev.lawlan.runline.engine.resource

import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.Serializable

@Serializable data class CreateResourceRequest(val name: String, val capacity: Int)

/** Both fields are optional; at least one must be given. */
@Serializable
data class UpdateResourceRequest(val capacity: Int? = null, val enabled: Boolean? = null)

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

/** A resource with who holds it and who waits for it; waiters are in the order they are served. */
@Serializable
data class ResourceResponse(
    val name: String,
    val capacity: Int,
    val enabled: Boolean,
    val createdBy: String,
    val createdAt: String,
    val updatedBy: String,
    val updatedAt: String,
    val holders: List<HolderDoc>,
    val waiters: List<WaiterDoc>,
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
      capacity = resource.capacity,
      enabled = resource.enabled,
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
  )
}

private fun seconds(since: Instant, now: Instant) =
    Duration.between(since, now).toMillis().coerceAtLeast(0) / 1000.0
