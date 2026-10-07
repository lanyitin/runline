package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.accessors.ResourceBinding
import dev.lawlan.runline.engine.config.ResourceSettings
import kotlinx.serialization.json.JsonObject

/**
 * What a resource type does beyond having a name and a capacity (ADR-019): which settings it
 * accepts and the accessor a run gets for it. The set of types is closed; there is one behavior per
 * type, chosen in [ResourceBehaviors], and no way to register another.
 */
interface ResourceBehavior {
  /** What is wrong with the settings and secret alias given for this type, or null. */
  fun problemWith(settings: JsonObject?, secretAlias: String?): InvalidResource?

  /**
   * The accessor for a run that holds [resource], bound to its settings as they are now; null for a
   * type that has no accessor. Fails with [ResourceUnavailable] when the entity cannot be used.
   */
  fun bind(resource: SharedResource): ResourceBinding?
}

/** The entity behind a resource cannot be used now; [resource] is named, nothing more. */
class ResourceUnavailable(val resource: String, cause: Throwable? = null) :
    RuntimeException("Shared resource '$resource' is unavailable", cause)

/** No entity, only a name and a capacity (ADR-007): no settings, no secret, no accessor. */
internal object CounterBehavior : ResourceBehavior {
  override fun problemWith(settings: JsonObject?, secretAlias: String?): InvalidResource? =
      when {
        !settings.isNullOrEmpty() -> InvalidResource.INVALID_SETTINGS
        secretAlias != null -> InvalidResource.INVALID_SECRET_ALIAS
        else -> null
      }

  override fun bind(resource: SharedResource): ResourceBinding? = null
}

/** The behavior of each type that can be defined; a type without one is not implemented yet. */
class ResourceBehaviors(private val byType: Map<ResourceType, ResourceBehavior>) {
  fun of(type: ResourceType): ResourceBehavior? = byType[type]

  companion object {
    /** Only the type that needs nothing from the environment. */
    fun countersOnly() = ResourceBehaviors(mapOf(ResourceType.COUNTER to CounterBehavior))

    /** Every type the Engine implements, with what it needs from its configuration. */
    fun forEngine(settings: ResourceSettings) =
        ResourceBehaviors(
            mapOf(
                ResourceType.COUNTER to CounterBehavior,
                ResourceType.FILE to FileBehavior(settings.root),
            )
        )
  }
}
