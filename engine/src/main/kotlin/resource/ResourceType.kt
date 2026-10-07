package dev.lawlan.runline.engine.resource

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

  companion object {
    /** The type named [wireName], or null when the name is not in the closed set. */
    fun fromWireName(wireName: String): ResourceType? = entries.firstOrNull {
      it.wireName == wireName
    }
  }
}
