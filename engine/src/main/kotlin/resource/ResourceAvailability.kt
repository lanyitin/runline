package dev.lawlan.runline.engine.resource

enum class ResourceProblemKind(
    /** The name of the problem in the API. */
    val wire: String,
    /** A few words saying what is wrong with the resource, for messages to people. */
    val label: String,
) {
  /** No resource of this name is defined. */
  UNKNOWN("unknown", "尚未定義"),

  /** The resource is defined but an administrator has disabled it. */
  DISABLED("disabled", "已停用"),

  /** The resource is usable but is not of the type the pipeline declared for it (ADR-019). */
  TYPE_MISMATCH("type_mismatch", "型別不符"),
}

data class ResourceProblem(val name: String, val kind: ResourceProblemKind)

/**
 * What was found out about some declared names: the definitions that exist and what is wrong.
 * [declaredTypes] are the types the declaring pipeline expects, by name; a name without one needs
 * nothing but capacity and fits a resource of any type. A declared type outside the closed set fits
 * no resource.
 */
class ResourceInspection(
    val defined: Map<String, SharedResource>,
    requested: Collection<String>,
    private val declaredTypes: Map<String, String> = emptyMap(),
) {
  /** What is wrong with the names asked for, in the order given. */
  val problems: List<ResourceProblem> = problemsFor(requested)

  /** What is wrong with [names], judged by the definitions found; no further lookup. */
  fun problemsFor(
      names: Collection<String>,
      types: Map<String, String> = declaredTypes,
  ): List<ResourceProblem> =
      names.distinct().mapNotNull {
        val resource = defined[it]
        when {
          resource == null -> ResourceProblem(it, ResourceProblemKind.UNKNOWN)
          !resource.enabled -> ResourceProblem(it, ResourceProblemKind.DISABLED)
          types[it]?.let { declared -> declared != resource.type.wireName } == true ->
              ResourceProblem(it, ResourceProblemKind.TYPE_MISMATCH)
          else -> null
        }
      }
}

/**
 * The one rule for whether a declared resource name can be used: it must be defined and enabled,
 * and of the declared type when the pipeline declared one. Used when a pipeline is uploaded (as a
 * warning), when a run is created (as a refusal) and when a waiting run is looked at again.
 */
class ResourceAvailability(private val store: ResourceStore) {
  /** What is wrong with the names, in the order given; empty when all can be used. */
  fun problems(
      names: Collection<String>,
      declaredTypes: Map<String, String> = emptyMap(),
  ): List<ResourceProblem> = inspect(names, declaredTypes).problems

  /** Looks the names up once; the result can judge any subset of them. */
  fun inspect(
      names: Collection<String>,
      declaredTypes: Map<String, String> = emptyMap(),
  ): ResourceInspection = ResourceInspection(store.findAll(names), names, declaredTypes)
}
