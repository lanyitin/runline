package samples.intake

import dev.lawlan.runline.core.AccessLimit
import dev.lawlan.runline.core.Param
import dev.lawlan.runline.core.Pipeline
import dev.lawlan.runline.core.PipelineContext
import dev.lawlan.runline.core.PipelineDefinition

/**
 * SAFE: a pipeline with a required parameter that has no default (`file`), for a webhook whose
 * caller says which file to handle on each call (ADR-036).
 */
@PipelineDefinition(
    name = "demo-intake",
    parameters =
        [
            Param("file", required = true),
            Param("language", required = false, default = "en"),
        ],
    network = AccessLimit(allow = []),
    processes = AccessLimit(allow = []),
)
class IntakePipeline : Pipeline {
  override fun run(context: PipelineContext) {
    println(
        "handling ${context.parameters.getValue("file")} in ${context.parameters.getValue("language")}"
    )
  }
}
