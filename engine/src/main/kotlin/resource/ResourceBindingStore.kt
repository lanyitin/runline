package dev.lawlan.runline.engine.resource

import java.time.Instant

/**
 * Whose pipeline a binding belongs to: an uploader and a pipeline name (ADR-025). The versions the
 * same uploader made of the pipeline share it; another uploader's pipeline of the same name is
 * another key and cannot be seen from this one (ADR-020).
 */
data class PipelineKey(val uploader: String, val pipeline: String)

/**
 * The resource chosen for a role of a pipeline (ADR-025). [role] and [resource] are two different
 * identities, and a binding to a resource that is not (or no longer) defined is kept as it is.
 * [setBy] is whoever set it: the uploader, or an administrator on the uploader's behalf.
 */
data class RoleBinding(
    val uploader: String,
    val pipeline: String,
    val role: String,
    val resource: String,
    val setBy: String,
    val setAt: Instant,
) {
  val key: PipelineKey
    get() = PipelineKey(uploader, pipeline)
}

/** Persistence of the bindings of roles to resources. */
interface ResourceBindingStore {
  /** The bindings of [key], by role. */
  fun forPipeline(key: PipelineKey): Map<String, RoleBinding> =
      forPipelines(listOf(key)).getValue(key)

  /** The bindings of each of [keys], by key and then by role; none gives an empty map. */
  fun forPipelines(keys: Collection<PipelineKey>): Map<PipelineKey, Map<String, RoleBinding>>

  /** Binds the role, replacing what it was bound to. */
  fun put(binding: RoleBinding)

  /** Removes the binding of [role]; false when it had none. */
  fun remove(key: PipelineKey, role: String): Boolean
}
