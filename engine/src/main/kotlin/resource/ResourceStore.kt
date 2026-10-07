package dev.lawlan.runline.engine.resource

import java.time.Instant
import kotlinx.serialization.json.JsonObject

/**
 * A shared resource an administrator has defined (ADR-007, ADR-019): a name, its [type], how many
 * runs may hold it at once, and whether pipelines may use it. Who holds it or waits for it is
 * runtime state of the Engine and is not part of the definition.
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
    /** Fixed when the resource is created. */
    val type: ResourceType = ResourceType.COUNTER,
    /** The non-secret settings of the [type]; a counter has none. */
    val settings: JsonObject = JsonObject(emptyMap()),
    /** The keystore alias of the secret, never the secret itself. */
    val secretAlias: String? = null,
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

  /** Removes the definition; false when there is no resource of that name. */
  fun delete(name: String): Boolean
}
