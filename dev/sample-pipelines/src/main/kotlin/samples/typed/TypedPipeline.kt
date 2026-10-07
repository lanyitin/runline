package samples.typed

import dev.lawlan.runline.core.AccessLimit
import dev.lawlan.runline.core.Pipeline
import dev.lawlan.runline.core.PipelineContext
import dev.lawlan.runline.core.PipelineDefinition
import dev.lawlan.runline.core.TypedResource

/**
 * SAFE, declares the shared resource `demo-printer` and expects it to be of the type `file`. The
 * demo defines `demo-printer` as a counter, so the pipeline's page warns that the types differ
 * (`resource_type_mismatch`) and a run of it is refused: it is here for the Console's resource
 * pages (WI-49), to show a declaration with a type and one whose type does not match. It never uses
 * the resource.
 */
@PipelineDefinition(
    name = "demo-typed",
    network = AccessLimit(allow = []),
    processes = AccessLimit(allow = []),
    typedResources = [TypedResource(name = "demo-printer", type = "file")],
)
class TypedPipeline : Pipeline {
  override fun run(context: PipelineContext) {
    println("expects demo-printer to be a file")
  }
}
