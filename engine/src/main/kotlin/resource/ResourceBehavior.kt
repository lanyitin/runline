package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.accessors.ResourceBinding
import dev.lawlan.runline.accessors.openai.OpenAiObserver
import dev.lawlan.runline.engine.config.ResourceSettings
import dev.lawlan.runline.engine.secret.NoSecretStore
import dev.lawlan.runline.engine.secret.SecretStore
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
   * What is wrong with a change of an existing resource: [settings] replace the stored ones as a
   * whole and [secretAlias] the alias, each only when not null. A change that names neither has
   * nothing to be wrong with.
   */
  fun problemWithChange(
      existing: SharedResource,
      settings: JsonObject?,
      secretAlias: String?,
  ): InvalidResource? {
    if (settings != null) {
      problemWith(settings, secretAlias ?: existing.secretAlias)?.let {
        return it
      }
    }
    return if (secretAlias != null) problemWith(settings ?: existing.settings, secretAlias)
    else null
  }

  /** The settings as they are stored, every effective value written out; given valid [settings]. */
  fun normalized(settings: JsonObject): JsonObject = settings

  /**
   * The most requests the entity can have to deal with at once, where the type can say, from the
   * resource's capacity and what each holder may do at once; null for a type that cannot.
   */
  fun concurrencyLimit(resource: SharedResource): Int? = null

  /**
   * The accessor for a run that holds [resource], bound to its settings as they are now; null for a
   * type that has no accessor. Fails with [ResourceUnavailable] when the entity cannot be used.
   */
  fun bind(resource: SharedResource): ResourceBinding?

  /**
   * Looks at the real entity and says what stops it from being used, or null when nothing does. May
   * block (that is what the limit on a check is for); called on a thread of the checker's.
   */
  fun check(resource: SharedResource): CheckFailure?
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

  override fun check(resource: SharedResource): CheckFailure? = null
}

/** The behavior of each type that can be defined; a type without one is not implemented yet. */
class ResourceBehaviors(private val byType: Map<ResourceType, ResourceBehavior>) {
  fun of(type: ResourceType): ResourceBehavior? = byType[type]

  companion object {
    /** Only the type that needs nothing from the environment. */
    fun countersOnly() = ResourceBehaviors(mapOf(ResourceType.COUNTER to CounterBehavior))

    /** Every type the Engine implements, with what it needs from its configuration. */
    fun forEngine(
        settings: ResourceSettings,
        secrets: SecretStore = NoSecretStore,
        openAiObserver: OpenAiObserver = OpenAiObserver.NONE,
    ) =
        ResourceBehaviors(
            mapOf(
                ResourceType.COUNTER to CounterBehavior,
                ResourceType.FILE to FileBehavior(settings.root, settings.maxReadBytes),
                ResourceType.OPENAI_COMPATIBLE to
                    OpenAiCompatibleBehavior(secrets, settings.checkTimeout, openAiObserver),
            )
        )
  }
}
