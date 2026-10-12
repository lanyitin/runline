package dev.lawlan.runline.engine.view

import dev.lawlan.runline.engine.artifact.StoredDefinition
import dev.lawlan.runline.engine.artifact.VersionOutcome
import dev.lawlan.runline.engine.artifact.ViewDoc
import dev.lawlan.runline.engine.auth.ApiIdentity

/** A declared view and whether it can be read now. */
data class ViewListing(val view: ViewDoc, val resource: ViewResource)

sealed interface ViewListResult {
  data class Found(val definition: StoredDefinition, val views: List<ViewListing>) : ViewListResult

  data class Ambiguous(val uploaders: List<String>) : ViewListResult

  /** No such version and pipeline, or one the caller may not see: never told apart. */
  data object NotFound : ViewListResult
}

/** Lists the views of a pipeline version; runs no query. */
class ViewCatalog(private val targets: ViewTargets) {
  fun list(
      contentHash: String,
      uploader: String?,
      pipeline: String,
      caller: ApiIdentity,
  ): ViewListResult =
      when (val found = targets.definition(contentHash, uploader, pipeline, caller)) {
        is VersionOutcome.Ambiguous -> ViewListResult.Ambiguous(found.uploaders)
        VersionOutcome.NotFound -> ViewListResult.NotFound
        is VersionOutcome.Resolved -> {
          val views = found.value.metadata.views
          val resources = targets.resourcesOf(found.value, views)
          ViewListResult.Found(found.value, views.zip(resources, ::ViewListing))
        }
      }
}
