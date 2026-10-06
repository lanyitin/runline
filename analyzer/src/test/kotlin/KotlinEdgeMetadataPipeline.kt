package demo

import dev.lawlan.runline.core.AccessLimit
import dev.lawlan.runline.core.FileAccess
import dev.lawlan.runline.core.FileMode
import dev.lawlan.runline.core.FileScope
import dev.lawlan.runline.core.Param
import dev.lawlan.runline.core.Pipeline
import dev.lawlan.runline.core.PipelineContext
import dev.lawlan.runline.core.PipelineDefinition

/**
 * Parameter and limit declarations at the edges of their defaults, for the comparison of the
 * analyzer's metadata with the one core reads by reflection. Only read as bytes or inspected
 * without initialisation.
 */
@PipelineDefinition(
    name = "kotlin-edge-metadata",
    parameters =
        [
            Param(name = "required-with-default", default = "ignored"),
            Param(name = "optional-no-default", required = false),
            Param(name = "optional-empty-default", required = false, default = ""),
            Param(name = "optional-with-default", required = false, default = "42"),
            Param(name = "explicitly-required", required = true),
        ],
    files =
        [
            FileAccess(scope = FileScope.PIPELINE_SHARED, mode = FileMode.READ_WRITE),
            FileAccess(scope = FileScope.RUN_PRIVATE, mode = FileMode.READ_ONLY),
        ],
    network = AccessLimit(unrestricted = false, allow = ["a.example", "b.example"]),
    processes = AccessLimit(unrestricted = true),
    resources = ["lock-a", "lock-b"],
)
class KotlinEdgeMetadataPipeline : Pipeline {
  override fun run(context: PipelineContext) {}
}
