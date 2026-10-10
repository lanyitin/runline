package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.engine.artifact.DefinitionStore
import dev.lawlan.runline.engine.artifact.StoredDefinition
import dev.lawlan.runline.engine.artifact.VersionOutcome
import dev.lawlan.runline.engine.artifact.VersionResolver
import dev.lawlan.runline.engine.artifact.Visibility
import dev.lawlan.runline.engine.artifact.visibility
import dev.lawlan.runline.engine.auth.ApiIdentity
import java.time.Clock
import org.slf4j.LoggerFactory

/** Why a binding is refused as invalid; [problem] is its name in the API. */
enum class InvalidBinding(val problem: String) {
  /** The pipeline version does not declare the role. */
  UNKNOWN_ROLE("unknown_role"),

  /** The resource is not of the type the role declared. */
  TYPE_MISMATCH("type_mismatch"),
}

/**
 * One role of a pipeline as the binding screen needs it (ADR-025): what it is, what it is bound to,
 * what it gets in the end and whether that can be used, and the resources it could be bound to.
 */
data class RoleStatus(
    val role: String,
    /** The type the pipeline expects; null when it declared the name only. */
    val type: String?,
    val purpose: String?,
    /** The explicit binding; null when the role gets the resource of its own name. */
    val binding: RoleBinding?,
    /** The name of the resource the role gets: the bound one, else the one of its own name. */
    val resource: String,
    /** That resource as defined; null when there is none. */
    val definition: SharedResource?,
    /** What stops the role from being used, as a run would find it; null when nothing does. */
    val problem: ResourceProblemKind?,
    /** The resources the role could be bound to: those of its type (any, for a role with none). */
    val candidates: List<SharedResource>,
) {
  val isDefault: Boolean
    get() = binding == null

  /** Whether the resource has the type the role expects; null when there is no resource. */
  val typeMatches: Boolean?
    get() = definition?.let { type == null || type == it.type.wireName }
}

/** The roles of one pipeline version, and the bindings of the pipeline that no role claims. */
data class BindingsView(
    val contentHash: String,
    /** Whose version, and so whose bindings. */
    val uploader: String,
    val pipeline: String,
    val roles: List<RoleStatus>,
    /** Bindings of roles this version no longer declares; kept until removed. */
    val stale: List<RoleBinding>,
    /** The uploaders of this content the caller can see: an administrator picks one of them. */
    val uploaders: List<String>,
)

sealed interface BindingsResult {
  data class Found(val view: BindingsView) : BindingsResult

  /** The caller can see several versions of the content and did not say whose. */
  data class Ambiguous(val uploaders: List<String>) : BindingsResult

  /** No such version and pipeline, or one the caller may not see. */
  data object DefinitionNotFound : BindingsResult

  /** The role is neither declared by the version nor kept as a binding of the uploader's. */
  data object RoleNotFound : BindingsResult

  /** There is no such resource to bind to. */
  data object ResourceNotFound : BindingsResult

  data class Invalid(val problem: InvalidBinding) : BindingsResult
}

/**
 * Which resource each role of a pipeline gets (ADR-025). Looking at them, setting and removing a
 * binding are open to developers and administrators alike, on the versions the caller may see; none
 * of them can create, change or delete a resource. A binding belongs to an uploader's pipeline (the
 * uploader and the name), so it holds for every version that uploader made of it and never reaches
 * another uploader's (ADR-020): the version a caller can see decides whose bindings they reach, so
 * a developer reaches their own and nobody else's, and an administrator names the uploader. Who set
 * or removed a binding is logged, with when, and nothing else of the resource but its name.
 */
class ResourceBindings(
    private val resolver: VersionResolver,
    private val definitions: DefinitionStore,
    private val bindings: ResourceBindingStore,
    private val resources: ResourceStore,
    private val availability: ResourceAvailability,
    private val clock: Clock,
) {
  private val log = LoggerFactory.getLogger(ResourceBindings::class.java)

  fun view(
      contentHash: String,
      uploader: String?,
      pipeline: String,
      caller: ApiIdentity,
  ): BindingsResult =
      withDefinition(contentHash, uploader, pipeline, caller.visibility) { d ->
        BindingsResult.Found(viewOf(d, caller.visibility))
      }

  fun bind(
      contentHash: String,
      uploader: String?,
      pipeline: String,
      role: String,
      resource: String,
      by: ApiIdentity,
  ): BindingsResult =
      withDefinition(contentHash, uploader, pipeline, by.visibility) { d ->
        if (role !in d.metadata.resources) {
          return@withDefinition BindingsResult.Invalid(InvalidBinding.UNKNOWN_ROLE)
        }
        val target =
            resources.find(resource) ?: return@withDefinition BindingsResult.ResourceNotFound
        val expected = d.metadata.resourceTypes[role]
        if (expected != null && expected != target.type.wireName) {
          return@withDefinition BindingsResult.Invalid(InvalidBinding.TYPE_MISMATCH)
        }
        val now = clock.instant()
        bindings.put(RoleBinding(d.uploadedBy, d.name, role, resource, by.name, now))
        log.info(
            "Resource binding set: role {} of pipeline {} of uploader {} is bound to resource {} " +
                "by {} at {}",
            role,
            d.name,
            d.uploadedBy,
            resource,
            by.name,
            now,
        )
        BindingsResult.Found(viewOf(d, by.visibility))
      }

  /** Removes the binding of [role]; a role that has none stays as it is. */
  fun unbind(
      contentHash: String,
      uploader: String?,
      pipeline: String,
      role: String,
      by: ApiIdentity,
  ): BindingsResult =
      withDefinition(contentHash, uploader, pipeline, by.visibility) { d ->
        val key = PipelineKey(d.uploadedBy, d.name)
        val before = bindings.forPipeline(key)[role]
        // A role the version does not declare and the uploader has no binding for is a role that
        // is not there, whoever else may have bound it: the answer is the same for every such name.
        if (before == null && role !in d.metadata.resources) {
          return@withDefinition BindingsResult.RoleNotFound
        }
        if (before != null && bindings.remove(key, role)) {
          log.info(
              "Resource binding removed: role {} of pipeline {} of uploader {} is no longer bound " +
                  "to resource {}, by {} at {}",
              role,
              d.name,
              d.uploadedBy,
              before.resource,
              by.name,
              clock.instant(),
          )
        }
        BindingsResult.Found(viewOf(d, by.visibility))
      }

  private fun withDefinition(
      contentHash: String,
      uploader: String?,
      pipeline: String,
      visibility: Visibility,
      then: (StoredDefinition) -> BindingsResult,
  ): BindingsResult =
      when (val version = resolver.resolve(contentHash, uploader, visibility)) {
        is VersionOutcome.Ambiguous -> BindingsResult.Ambiguous(version.uploaders)
        VersionOutcome.NotFound -> BindingsResult.DefinitionNotFound
        is VersionOutcome.Resolved ->
            definitions.find(contentHash, version.value, pipeline)?.let(then)
                ?: BindingsResult.DefinitionNotFound
      }

  private fun viewOf(definition: StoredDefinition, visibility: Visibility): BindingsView {
    val metadata = definition.metadata
    val roles = metadata.resources
    val key = PipelineKey(definition.uploadedBy, definition.name)
    val bound = bindings.forPipeline(key)
    val inspection = availability.inspect(key, roles)
    val all = resources.list()
    return BindingsView(
        definition.contentHash,
        definition.uploadedBy,
        definition.name,
        roles.map { role ->
          val type = metadata.resourceTypes[role]
          val resolved = inspection.resolve(key, role)
          RoleStatus(
              role,
              type,
              metadata.resourcePurposes[role],
              bound[role],
              resolved.resource,
              resolved.definition,
              resolved.problem(type)?.kind,
              all.filter { type == null || it.type.wireName == type },
          )
        },
        bound.values.filter { it.role !in roles },
        resolver.visibleUploaders(definition.contentHash, visibility),
    )
  }
}
