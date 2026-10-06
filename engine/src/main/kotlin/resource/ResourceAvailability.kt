package dev.lawlan.runline.engine.resource

enum class ResourceProblemKind {
  /** No resource of this name is defined. */
  UNKNOWN,

  /** The resource is defined but an administrator has disabled it. */
  DISABLED,
}

data class ResourceProblem(val name: String, val kind: ResourceProblemKind)

/** What was found out about some declared names: the definitions that exist and what is wrong. */
class ResourceInspection(
    val defined: Map<String, SharedResource>,
    requested: Collection<String>,
) {
  /** What is wrong with the names asked for, in the order given. */
  val problems: List<ResourceProblem> = problemsFor(requested)

  /** What is wrong with [names], judged by the definitions found; no further lookup. */
  fun problemsFor(names: Collection<String>): List<ResourceProblem> =
      names.distinct().mapNotNull {
        val resource = defined[it]
        when {
          resource == null -> ResourceProblem(it, ResourceProblemKind.UNKNOWN)
          !resource.enabled -> ResourceProblem(it, ResourceProblemKind.DISABLED)
          else -> null
        }
      }
}

/**
 * The one rule for whether a declared resource name can be used: it must be defined and enabled.
 * Used when a pipeline is uploaded (as a warning), when a run is created (as a refusal) and when a
 * waiting run is looked at again.
 */
class ResourceAvailability(private val store: ResourceStore) {
  /** What is wrong with the names, in the order given; empty when all can be used. */
  fun problems(names: Collection<String>): List<ResourceProblem> = inspect(names).problems

  /** Looks the names up once; the result can judge any subset of them. */
  fun inspect(names: Collection<String>): ResourceInspection =
      ResourceInspection(store.findAll(names), names)
}
