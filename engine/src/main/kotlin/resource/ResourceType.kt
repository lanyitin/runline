package dev.lawlan.runline.engine.resource

import kotlinx.serialization.json.JsonObject

/**
 * The closed set of shared resource types (ADR-019). A type is added by changing the Engine, never
 * by registering one at run time: there is no extension point, by decision.
 */
enum class ResourceType(
    /** The name used in the API, in the database and in a pipeline's declaration. */
    val wireName: String
) {
  /** No entity, only a name and a capacity: the meaning resources always had (ADR-007). */
  COUNTER("counter"),
  FILE("file"),
  JDBC_POOL("jdbc-pool"),
  OPENAI_COMPATIBLE("openai-compatible");

  /**
   * Whether a resource of this type can be defined yet. The types with an entity arrive with their
   * own work items; until then a request for one is refused as unsupported.
   */
  val supported: Boolean
    get() = this == COUNTER

  /**
   * What is wrong with the settings and secret alias given for this type, or null. A type has no
   * settings and no secret unless it says otherwise, which is the case of a counter.
   */
  fun problemWith(settings: JsonObject?, secretAlias: String?): InvalidResource? =
      when {
        !settings.isNullOrEmpty() -> InvalidResource.INVALID_SETTINGS
        secretAlias != null -> InvalidResource.INVALID_SECRET_ALIAS
        else -> null
      }

  companion object {
    /** The type named [wireName], or null when the name is not in the closed set. */
    fun fromWireName(wireName: String): ResourceType? = entries.firstOrNull {
      it.wireName == wireName
    }
  }
}
