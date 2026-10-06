package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.engine.auth.ApiIdentity
import org.slf4j.LoggerFactory

enum class InvalidResource {
  /** The name is empty, too long or uses characters other than letters, digits, `.`, `_`, `-`. */
  NAME,

  /** The capacity is below one. */
  CAPACITY,

  /** An update that changes nothing. */
  NOTHING_TO_CHANGE,
}

sealed interface CreateResourceResult {
  data class Created(val resource: SharedResource) : CreateResourceResult

  data object AlreadyExists : CreateResourceResult

  data class Invalid(val problem: InvalidResource) : CreateResourceResult
}

sealed interface UpdateResourceResult {
  data class Updated(val resource: SharedResource) : UpdateResourceResult

  data object NotFound : UpdateResourceResult

  data class Invalid(val problem: InvalidResource) : UpdateResourceResult
}

/**
 * The administrator's definition of shared resources: create, look up, change the capacity, enable
 * and disable. Who made each change is recorded as the name of the caller (ADR-012). [onChange] is
 * called after a change that may let waiting runs proceed or fail.
 */
class ResourceAdmin(
    private val store: ResourceStore,
    private val clock: java.time.Clock,
    private val onChange: () -> Unit,
) {
  private val log = LoggerFactory.getLogger(ResourceAdmin::class.java)

  fun create(name: String, capacity: Int, by: ApiIdentity): CreateResourceResult {
    if (!NAME.matches(name)) return CreateResourceResult.Invalid(InvalidResource.NAME)
    if (capacity < 1) return CreateResourceResult.Invalid(InvalidResource.CAPACITY)
    val now = clock.instant()
    val resource = SharedResource(name, capacity, true, by.name, now, by.name, now)
    if (!store.insert(resource)) return CreateResourceResult.AlreadyExists
    log.info("Shared resource {} created with capacity {} by {}", name, capacity, by.name)
    return CreateResourceResult.Created(resource)
  }

  fun update(
      name: String,
      capacity: Int?,
      enabled: Boolean?,
      by: ApiIdentity,
  ): UpdateResourceResult {
    if (capacity == null && enabled == null) {
      return UpdateResourceResult.Invalid(InvalidResource.NOTHING_TO_CHANGE)
    }
    if (capacity != null && capacity < 1) {
      return UpdateResourceResult.Invalid(InvalidResource.CAPACITY)
    }
    val updated =
        store.update(name, capacity, enabled, by.name, clock.instant())
            ?: return UpdateResourceResult.NotFound
    log.info(
        "Shared resource {} changed by {}: capacity {}, enabled {}",
        name,
        by.name,
        updated.capacity,
        updated.enabled,
    )
    onChange()
    return UpdateResourceResult.Updated(updated)
  }

  fun find(name: String): SharedResource? = store.find(name)

  fun list(): List<SharedResource> = store.list()

  private companion object {
    val NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")
  }
}
