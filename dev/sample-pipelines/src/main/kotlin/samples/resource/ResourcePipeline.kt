package samples.resource

import dev.lawlan.runline.core.AccessLimit
import dev.lawlan.runline.core.Pipeline
import dev.lawlan.runline.core.PipelineContext
import dev.lawlan.runline.core.PipelineDefinition

/**
 * SAFE, declares the shared resource `demo-printer`: creating a run is refused (409
 * resources_unavailable) until an administrator defines it; with capacity 1, a second run waits
 * (WAITING_FOR_RESOURCES) while the first holds it.
 */
@PipelineDefinition(
    name = "demo-resource",
    network = AccessLimit(allow = []),
    processes = AccessLimit(allow = []),
    resources = ["demo-printer"],
)
class ResourcePipeline : Pipeline {
  override fun run(context: PipelineContext) {
    println("holding demo-printer for 20 seconds")
    for (second in 1..20) {
      Thread.sleep(1000)
      if (second % 5 == 0) println("still printing, $second/20")
    }
    println("released")
  }
}
