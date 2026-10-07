package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.engine.auth.ApiIdentity
import java.util.UUID

/**
 * A resource's definition together with who holds it and who waits for it right now, and which
 * pipeline definitions declare it.
 */
data class ResourceView(
    val resource: SharedResource,
    val activity: ResourceActivity,
    val declarations: ResourceDeclarations,
)

sealed interface ForceReleaseOutcome {
  data class Released(val holder: Holder) : ForceReleaseOutcome

  data object ResourceNotFound : ForceReleaseOutcome

  /** The resource exists but the run does not hold it. */
  data object NotHeld : ForceReleaseOutcome
}

/**
 * What an administrator sees of shared resources: the definitions joined with the runtime state
 * kept by the [ResourceCoordinator] and with the pipeline definitions that declare them, and the
 * forced release of a holder.
 */
class ResourceCatalog(
    private val store: ResourceStore,
    private val coordinator: ResourceCoordinator,
    private val declarations: ResourceDeclarationStore,
) {
  fun list(): List<ResourceView> = views(store.list())

  fun find(name: String): ResourceView? = store.find(name)?.let { views(listOf(it)).single() }

  fun forceRelease(name: String, runId: UUID, by: ApiIdentity): ForceReleaseOutcome {
    store.find(name) ?: return ForceReleaseOutcome.ResourceNotFound
    return when (val result = coordinator.forceRelease(name, runId, by)) {
      is ForceReleaseResult.Released -> ForceReleaseOutcome.Released(result.holder)
      ForceReleaseResult.NotHeld -> ForceReleaseOutcome.NotHeld
    }
  }

  private fun views(resources: List<SharedResource>): List<ResourceView> {
    val declaredBy = declarations.declaredBy(resources.map { it.name })
    return resources.map {
      ResourceView(it, coordinator.activity(it.name), declaredBy.getValue(it.name))
    }
  }
}
