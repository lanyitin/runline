package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.engine.auth.ApiIdentity
import java.util.UUID

/** A resource's definition together with who holds it and who waits for it right now. */
data class ResourceView(val resource: SharedResource, val activity: ResourceActivity)

sealed interface ForceReleaseOutcome {
  data class Released(val holder: Holder) : ForceReleaseOutcome

  data object ResourceNotFound : ForceReleaseOutcome

  /** The resource exists but the run does not hold it. */
  data object NotHeld : ForceReleaseOutcome
}

/**
 * What an administrator sees of shared resources: the definitions joined with the runtime state
 * kept by the [ResourceCoordinator], and the forced release of a holder.
 */
class ResourceCatalog(
    private val store: ResourceStore,
    private val coordinator: ResourceCoordinator,
) {
  fun list(): List<ResourceView> = store.list().map(::view)

  fun find(name: String): ResourceView? = store.find(name)?.let(::view)

  fun forceRelease(name: String, runId: UUID, by: ApiIdentity): ForceReleaseOutcome {
    store.find(name) ?: return ForceReleaseOutcome.ResourceNotFound
    return when (val result = coordinator.forceRelease(name, runId, by)) {
      is ForceReleaseResult.Released -> ForceReleaseOutcome.Released(result.holder)
      ForceReleaseResult.NotHeld -> ForceReleaseOutcome.NotHeld
    }
  }

  private fun view(resource: SharedResource) =
      ResourceView(resource, coordinator.activity(resource.name))
}
