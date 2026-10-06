package demo

import dev.lawlan.runline.core.AccessLimit
import dev.lawlan.runline.core.Pipeline
import dev.lawlan.runline.core.PipelineContext
import dev.lawlan.runline.core.PipelineDefinition
import kotlin.system.exitProcess

/**
 * Compiled by the Kotlin compiler as test input for the safety analyzer. It is only ever read as
 * bytes; the tests never load or run it.
 */
@PipelineDefinition(
    name = "kotlin-exit",
    network = AccessLimit(allow = []),
    processes = AccessLimit(allow = []),
)
class KotlinExitPipeline : Pipeline {
  override fun run(context: PipelineContext) {
    exitProcess(1)
  }
}
