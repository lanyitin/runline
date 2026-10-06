package dev.lawlan.runline.engine.resource

import java.time.Instant

/**
 * A shared resource an administrator has defined (ADR-007): a name, how many runs may hold it at
 * once, and whether pipelines may use it. Who holds it or waits for it is runtime state of the
 * Engine and is not part of the definition.
 */
data class SharedResource(
    val name: String,
    /** 1 is mutual exclusion, more is a limit on concurrent holders. */
    val capacity: Int,
    val enabled: Boolean,
    val createdBy: String,
    val createdAt: Instant,
    val updatedBy: String,
    val updatedAt: Instant,
)

/** Persistence of resource definitions. */
interface ResourceStore {
  /** Stores [resource]; false, and nothing changes, when a resource of that name exists. */
  fun insert(resource: SharedResource): Boolean

  fun find(name: String): SharedResource?

  /** The definitions that exist among [names], by name. */
  fun findAll(names: Collection<String>): Map<String, SharedResource>

  /** Every definition, ordered by name. */
  fun list(): List<SharedResource>

  /**
   * Changes the capacity and/or the enabled flag (null leaves it as is) and records who and when.
   * Returns the definition as updated, or null when there is no such resource.
   */
  fun update(
      name: String,
      capacity: Int?,
      enabled: Boolean?,
      by: String,
      at: Instant,
  ): SharedResource?
}
