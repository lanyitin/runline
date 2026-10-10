package dev.lawlan.runline.engine.resource

enum class ResourceProblemKind(
    /** The name of the problem in the API. */
    val wire: String,
    /** A few words saying what is wrong with the resource, for messages to people. */
    val label: String,
) {
  /** The role is bound to a resource that is not defined (it may have been deleted). */
  UNKNOWN("unknown", "尚未定義"),

  /** The role has no binding and no resource of its own name is defined (ADR-025). */
  UNBOUND("unbound", "未綁定，且尚未定義同名資源"),

  /** The resource is defined but an administrator has disabled it. */
  DISABLED("disabled", "已停用"),

  /** The resource is usable but is not of the type the pipeline declared for it (ADR-019). */
  TYPE_MISMATCH("type_mismatch", "型別不符"),
}

/**
 * What is wrong with one role of a pipeline: [role] is the name the pipeline uses, [resource] the
 * resource it was resolved to (the one it is bound to, else the one of its own name).
 */
data class ResourceProblem(
    val role: String,
    val kind: ResourceProblemKind,
    val resource: String = role,
) {
  /** For messages to people: the role, and the resource too when it has another name. */
  fun describe(): String =
      if (resource == role) "$role（${kind.label}）" else "$role（綁定到「$resource」：${kind.label}）"
}

/**
 * A role of a pipeline resolved to a resource (ADR-025): [bound] is the resource it was explicitly
 * bound to, null when it has no binding and so gets the resource of its own name. [definition] is
 * that resource as defined, null when none is.
 */
class ResolvedRole(
    val role: String,
    val bound: String?,
    val definition: SharedResource?,
) {
  /** The name of the resource the role gets. */
  val resource: String
    get() = bound ?: role

  /** What is wrong with it given the type the pipeline declared for the role, or null. */
  fun problem(declaredType: String?): ResourceProblem? {
    val kind =
        when {
          definition == null ->
              if (bound == null) ResourceProblemKind.UNBOUND else ResourceProblemKind.UNKNOWN
          !definition.enabled -> ResourceProblemKind.DISABLED
          declaredType != null && declaredType != definition.type.wireName ->
              ResourceProblemKind.TYPE_MISMATCH
          else -> return null
        }
    return ResourceProblem(role, kind, resource)
  }
}

/**
 * What was found out about the roles of some pipelines: their bindings and the resources they lead
 * to. A role's declared type is what the pipeline expects of the resource; a role without one needs
 * nothing but capacity and fits a resource of any type. A declared type outside the closed set fits
 * no resource.
 */
class ResourceInspection(
    /** The resources that exist among those the roles lead to, by name. */
    val defined: Map<String, SharedResource>,
    private val bindings: Map<PipelineKey, Map<String, RoleBinding>>,
) {
  fun resolve(pipeline: PipelineKey, role: String): ResolvedRole {
    val bound = bindings[pipeline]?.get(role)?.resource
    return ResolvedRole(role, bound, defined[bound ?: role])
  }

  /**
   * What is wrong with [roles] of [pipeline] (an uploader's), in the order given; no further
   * lookup.
   */
  fun problemsFor(
      pipeline: PipelineKey,
      roles: Collection<String>,
      types: Map<String, String> = emptyMap(),
  ): List<ResourceProblem> =
      roles.distinct().mapNotNull { resolve(pipeline, it).problem(types[it]) }
}

/**
 * The one rule for whether a declared role can be used: it resolves to a resource (the one it is
 * bound to, else the one of its own name, ADR-025), which must be defined and enabled, and of the
 * declared type when the pipeline declared one. Used when a pipeline is uploaded (as a warning),
 * when a run is created (as a refusal) and when a waiting run is looked at again.
 */
class ResourceAvailability(
    private val store: ResourceStore,
    private val bindings: ResourceBindingStore,
) {
  /** What is wrong with the roles, in the order given; empty when all can be used. */
  fun problems(
      pipeline: PipelineKey,
      roles: Collection<String>,
      declaredTypes: Map<String, String> = emptyMap(),
  ): List<ResourceProblem> = inspect(pipeline, roles).problemsFor(pipeline, roles, declaredTypes)

  fun inspect(pipeline: PipelineKey, roles: Collection<String>): ResourceInspection =
      inspect(mapOf(pipeline to roles))

  /**
   * Looks the bindings and resources of the roles of each pipeline up once; it judges any subset.
   */
  fun inspect(rolesByPipeline: Map<PipelineKey, Collection<String>>): ResourceInspection {
    val bound = bindings.forPipelines(rolesByPipeline.keys)
    val names = rolesByPipeline.flatMap { (pipeline, roles) ->
      roles.map { bound.getValue(pipeline)[it]?.resource ?: it }
    }
    return ResourceInspection(store.findAll(names), bound)
  }
}
