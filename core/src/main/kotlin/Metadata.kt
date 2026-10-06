package dev.lawlan.runline.core

/** The only two places a pipeline may touch through its context. */
enum class FileScope {
  /** Shared by all runs of the same pipeline and kept across runs. */
  PIPELINE_SHARED,

  /** Private to a single run and removed when the run ends. */
  RUN_PRIVATE,
}

enum class FileMode(val writable: Boolean) {
  READ_ONLY(false),
  READ_WRITE(true),
}

/** Allowed range for an IO category that has no inherent boundary (network, external processes). */
sealed interface AccessPolicy {
  data object Unrestricted : AccessPolicy

  data class Allow(val entries: Set<String>) : AccessPolicy
}

data class ParameterSpec(val name: String, val required: Boolean, val default: String?)

data class PipelineMetadata(
    val name: String,
    val parameters: List<ParameterSpec>,
    val files: Map<FileScope, FileMode>,
    val network: AccessPolicy,
    val processes: AccessPolicy,
    val resources: Set<String>,
)

/** Applies defaults and validates [supplied] against the declared parameters. */
fun PipelineMetadata.resolveParameters(supplied: Map<String, String>): Map<String, String> {
  val unknown = supplied.keys - parameters.map { it.name }.toSet()
  require(unknown.isEmpty()) { "Pipeline '$name': undeclared parameter(s) ${unknown.sorted()}" }
  return parameters.associate { p ->
    p.name to
        (supplied[p.name]
            ?: p.default
            ?: throw IllegalArgumentException(
                "Pipeline '$name': missing required parameter '${p.name}'"
            ))
  }
}
