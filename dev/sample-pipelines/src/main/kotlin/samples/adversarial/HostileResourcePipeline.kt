package samples.adversarial

import dev.lawlan.runline.core.AccessLimit
import dev.lawlan.runline.core.Pipeline
import dev.lawlan.runline.core.PipelineContext
import dev.lawlan.runline.core.PipelineDefinition

/**
 * For the security check of the Console (WI-37): declares a shared resource whose name is markup.
 * The Engine takes it (upload warning "not defined"), and refuses a run of it (409
 * resources_unavailable) with the name in the answer, so the Console has the name to show in the
 * warning and in the refusal.
 */
@PipelineDefinition(
    name = "adversarial-resource",
    network = AccessLimit(allow = []),
    processes = AccessLimit(allow = []),
    resources = ["<u id=\"pwn-resource\" onclick=\"window.__xss='resource'\">r</u>"],
)
class HostileResourcePipeline : Pipeline {
  override fun run(context: PipelineContext) = println("never run")
}
