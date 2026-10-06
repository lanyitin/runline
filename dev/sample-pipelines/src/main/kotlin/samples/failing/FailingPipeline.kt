package samples.failing

import dev.lawlan.runline.core.AccessLimit
import dev.lawlan.runline.core.Param
import dev.lawlan.runline.core.Pipeline
import dev.lawlan.runline.core.PipelineContext
import dev.lawlan.runline.core.PipelineDefinition

/** SAFE, but ends FAILED after a few lines: to see the failure panel (type, message, trace). */
@PipelineDefinition(
    name = "demo-failing",
    parameters = [Param("reason", required = false, default = "the demo failed on purpose")],
    network = AccessLimit(allow = []),
    processes = AccessLimit(allow = []),
)
class FailingPipeline : Pipeline {
  override fun run(context: PipelineContext) {
    println("preparing")
    Thread.sleep(2000)
    println("about to fail")
    throw IllegalStateException(context.parameters.getValue("reason"))
  }
}
