package samples.adversarial

import dev.lawlan.runline.core.Pipeline
import dev.lawlan.runline.core.PipelineContext
import dev.lawlan.runline.core.PipelineDefinition

/**
 * For the security check of the Console (WI-37): the Engine refuses a jar with this name
 * (invalid_pipeline_name) and says the name back in its message, so the upload page shows a refusal
 * that carries markup.
 */
@PipelineDefinition(
    name =
        "<img id=\"pwn-name\" src=\"http://evil.invalid/name.png\" onerror=\"window.__xss='name'\">"
)
class RejectedNamePipeline : Pipeline {
  override fun run(context: PipelineContext) = println("never run")
}
