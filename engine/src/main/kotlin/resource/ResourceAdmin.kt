package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.engine.auth.ApiIdentity
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory

/** Why a request to create or change a resource is refused; [problem] is its name in the API. */
enum class InvalidResource(val problem: String) {
  /** The name is empty, too long or uses characters other than letters, digits, `.`, `_`, `-`. */
  NAME("name"),

  /** The capacity is below one. */
  CAPACITY("capacity"),

  /** An update that changes nothing. */
  NOTHING_TO_CHANGE("nothing_to_change"),

  /** The type is not one of the closed set (ADR-019). */
  UNKNOWN_TYPE("unknown_type"),

  /** The type is in the set but cannot be created yet. */
  UNSUPPORTED_TYPE("unsupported_type"),

  /** Settings the type does not have, or that do not fit it. */
  INVALID_SETTINGS("invalid_settings"),

  /** A secret alias on a type that has no secret, or one that is not well formed. */
  INVALID_SECRET_ALIAS("invalid_secret_alias"),

  /** The path of a `file` resource is not inside the resource root (ADR-019). */
  PATH_OUTSIDE_ROOT("path_outside_root"),

  /** The path of a `file` resource cannot be used now: no root, no directory, or no access. */
  PATH_UNUSABLE("path_unusable"),

  /** A change of name: a resource is identified by its name for good. */
  IMMUTABLE_NAME("immutable_name"),

  /** A change of type: to change the type, delete the resource and define it again. */
  IMMUTABLE_TYPE("immutable_type"),
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
 * and disable. The type and the name of a resource are fixed when it is created (ADR-019). Who made
 * each change is recorded as the name of the caller (ADR-012). [onChange] is called after a change
 * that may let waiting runs proceed or fail.
 */
class ResourceAdmin(
    private val store: ResourceStore,
    private val clock: java.time.Clock,
    private val behaviors: ResourceBehaviors,
    private val onChange: () -> Unit,
) {
  private val log = LoggerFactory.getLogger(ResourceAdmin::class.java)

  fun create(
      name: String,
      capacity: Int,
      by: ApiIdentity,
      type: String? = null,
      settings: JsonObject? = null,
      secretAlias: String? = null,
  ): CreateResourceResult {
    if (!NAME.matches(name)) return CreateResourceResult.Invalid(InvalidResource.NAME)
    if (capacity < 1) return CreateResourceResult.Invalid(InvalidResource.CAPACITY)
    val resourceType =
        if (type == null) ResourceType.COUNTER
        else
            ResourceType.fromWireName(type)
                ?: return CreateResourceResult.Invalid(InvalidResource.UNKNOWN_TYPE)
    val behavior =
        behaviors.of(resourceType)
            ?: return CreateResourceResult.Invalid(InvalidResource.UNSUPPORTED_TYPE)
    behavior.problemWith(settings, secretAlias)?.let {
      return CreateResourceResult.Invalid(it)
    }
    val now = clock.instant()
    val resource =
        SharedResource(
            name,
            capacity,
            true,
            by.name,
            now,
            by.name,
            now,
            resourceType,
            settings ?: JsonObject(emptyMap()),
            secretAlias,
        )
    if (!store.insert(resource)) return CreateResourceResult.AlreadyExists
    log.info(
        "Shared resource {} ({}) created with capacity {} by {}",
        name,
        resourceType.wireName,
        capacity,
        by.name,
    )
    return CreateResourceResult.Created(resource)
  }

  fun update(
      name: String,
      capacity: Int?,
      enabled: Boolean?,
      by: ApiIdentity,
      newName: String? = null,
      type: String? = null,
      settings: JsonObject? = null,
      secretAlias: String? = null,
  ): UpdateResourceResult {
    if (newName != null) return UpdateResourceResult.Invalid(InvalidResource.IMMUTABLE_NAME)
    if (type != null) return UpdateResourceResult.Invalid(InvalidResource.IMMUTABLE_TYPE)
    if (capacity == null && enabled == null && settings == null && secretAlias == null) {
      return UpdateResourceResult.Invalid(InvalidResource.NOTHING_TO_CHANGE)
    }
    if (capacity != null && capacity < 1) {
      return UpdateResourceResult.Invalid(InvalidResource.CAPACITY)
    }
    val existing = store.find(name) ?: return UpdateResourceResult.NotFound
    behaviors.of(existing.type)?.problemWith(settings, secretAlias)?.let {
      return UpdateResourceResult.Invalid(it)
    }
    val updated =
        store.update(name, capacity, enabled, by.name, clock.instant(), settings)
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
