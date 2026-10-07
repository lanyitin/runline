package samples.usage

import dev.lawlan.runline.core.AccessLimit
import dev.lawlan.runline.core.OpenAiRequest
import dev.lawlan.runline.core.Pipeline
import dev.lawlan.runline.core.PipelineContext
import dev.lawlan.runline.core.PipelineDefinition
import dev.lawlan.runline.core.TypedResource

/**
 * SAFE, uses the `openai-compatible` resource `demo-llm`: one chat completion, which is in flight
 * for as long as the service takes to answer. It is here for the Console's resource cards (WI-50),
 * to show a request in flight while a real run waits for the service.
 */
@PipelineDefinition(
    name = "demo-llm-usage",
    network = AccessLimit(allow = []),
    processes = AccessLimit(allow = []),
    typedResources = [TypedResource(name = "demo-llm", type = "openai-compatible")],
)
class LlmUsagePipeline : Pipeline {
  override fun run(context: PipelineContext) {
    val answer =
        context.accessors
            .openAiCompatible("demo-llm")
            .call(
                OpenAiRequest(
                    "chat.completions",
                    """{"model":"small","messages":[{"role":"user","content":"hi"}]}""",
                )
            )
    println("answered: ${answer.status}")
  }
}
