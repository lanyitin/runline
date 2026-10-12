package dev.lawlan.runline.engine.view

import dev.lawlan.runline.engine.artifact.DefinitionStore
import dev.lawlan.runline.engine.artifact.StoredDefinition
import dev.lawlan.runline.engine.artifact.VersionOutcome
import dev.lawlan.runline.engine.artifact.VersionResolver
import dev.lawlan.runline.engine.artifact.ViewDoc
import dev.lawlan.runline.engine.artifact.flatten
import dev.lawlan.runline.engine.artifact.visibility
import dev.lawlan.runline.engine.auth.ApiIdentity
import dev.lawlan.runline.engine.resource.PipelineKey
import dev.lawlan.runline.engine.resource.ResourceAvailability
import dev.lawlan.runline.engine.resource.ResourceProblemKind
import dev.lawlan.runline.engine.resource.SharedResource

/** The resource a view reads, as a run of the same pipeline would get it (ADR-025). */
sealed interface ViewResource {
  data class Usable(val resource: SharedResource) : ViewResource

  /** The role cannot be used; [kind] says why, in the words of a run's refusal. */
  data class Unusable(val kind: ResourceProblemKind) : ViewResource
}

/**
 * Finds what a request for a view is about: the version the caller may see (the same rule as for
 * resource bindings, ADR-020 and ADR-025, so that another uploader's version and one that does not
 * exist cannot be told apart), and the resource the view's role resolves to by the rule runs use.
 */
class ViewTargets(
    private val resolver: VersionResolver,
    private val definitions: DefinitionStore,
    private val availability: ResourceAvailability,
) {
  fun definition(
      contentHash: String,
      uploader: String?,
      pipeline: String,
      caller: ApiIdentity,
  ): VersionOutcome<StoredDefinition> =
      resolver.resolve(contentHash, uploader, caller.visibility).flatten { owner ->
        definitions.find(contentHash, owner, pipeline)?.let { VersionOutcome.Resolved(it) }
            ?: VersionOutcome.NotFound
      }

  /** What each of [views] resolves to, in the order given. */
  fun resourcesOf(definition: StoredDefinition, views: List<ViewDoc>): List<ViewResource> {
    val key = PipelineKey(definition.uploadedBy, definition.name)
    val inspection = availability.inspect(key, views.map { it.resource })
    return views.map { view ->
      val resolved = inspection.resolve(key, view.resource)
      val problem = resolved.problem(definition.metadata.resourceTypes[view.resource])
      if (problem != null) ViewResource.Unusable(problem.kind)
      else ViewResource.Usable(checkNotNull(resolved.definition))
    }
  }
}
