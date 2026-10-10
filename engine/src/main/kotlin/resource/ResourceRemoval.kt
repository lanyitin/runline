package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.engine.auth.ApiIdentity
import org.slf4j.LoggerFactory

/** What deleting a resource would touch, as of now; nothing has been changed. */
data class RemovalPreview(
    val resource: String,
    /** Pipeline definitions that declare the resource; they stay, and their runs are refused. */
    val definitions: Int,
    /** Triggers bound to those definitions. */
    val triggers: Int,
    val holders: Int,
    val waiters: Int,
) {
  /** Deleting would be refused: runs hold the resource or wait for it. */
  val inUse: Boolean
    get() = holders > 0 || waiters > 0
}

/**
 * Deleting a shared resource (ADR-019 point 8). Only a resource that no run holds or waits for can
 * go; the pipeline definitions and triggers that declare it do not stop it, and their runs are
 * refused as unknown afterwards. The decision is made by the [ResourceCoordinator], under the lock
 * that also grants resources, so a delete and an acquisition cannot both succeed. Who deleted is
 * recorded as the name of the caller (ADR-012). What the resource's type keeps for it goes with it
 * (the pool of a `jdbc-pool`, WI-65); deleting a file resource never deletes the file.
 */
class ResourceRemoval(
    private val store: ResourceStore,
    private val coordinator: ResourceCoordinator,
    private val declarations: ResourceDeclarationStore,
    private val behaviors: ResourceBehaviors,
) {
  private val log = LoggerFactory.getLogger(ResourceRemoval::class.java)

  /** What deleting [name] would touch; null when there is no such resource. */
  fun preview(name: String): RemovalPreview? {
    store.find(name) ?: return null
    val declared = declarations.declaredBy(listOf(name)).getValue(name)
    val activity = coordinator.activity(name)
    return RemovalPreview(
        name,
        declared.definitions.size,
        declared.triggerCount,
        activity.holders.size,
        activity.waiters.size,
    )
  }

  /** Deletes [name] unless runs hold it or wait for it. */
  fun remove(name: String, by: ApiIdentity): RemovalOutcome {
    val outcome =
        coordinator.removeWhenUnused(name) {
          val type = store.find(name)?.type ?: return@removeWhenUnused false
          store.delete(name).also { if (it) behaviors.of(type)?.removed(name) }
        }
    when (outcome) {
      RemovalOutcome.Removed -> log.info("Shared resource {} deleted by {}", name, by.name)
      is RemovalOutcome.InUse ->
          log.info(
              "Deleting shared resource {} refused for {}: {} holder(s), {} waiter(s)",
              name,
              by.name,
              outcome.holders,
              outcome.waiters,
          )
      RemovalOutcome.NotFound -> Unit
    }
    return outcome
  }
}
