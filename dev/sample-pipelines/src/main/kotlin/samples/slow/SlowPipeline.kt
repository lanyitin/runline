package samples.slow

import dev.lawlan.runline.core.AccessLimit
import dev.lawlan.runline.core.Param
import dev.lawlan.runline.core.Pipeline
import dev.lawlan.runline.core.PipelineContext
import dev.lawlan.runline.core.PipelineDefinition

/**
 * SAFE, takes tens of seconds, prints many lines: to watch QUEUED -> RUNNING -> SUCCEEDED, the log,
 * and to cancel it half way (Thread.sleep ends when the run is asked to stop).
 */
@PipelineDefinition(
    name = "demo-slow",
    parameters =
        [
            Param("label", required = false, default = "demo"),
            Param("steps", required = false, default = "30"),
            Param("delayMillis", required = false, default = "1000"),
        ],
    // Declared and empty: nothing is unrestricted, so the pipeline is judged SAFE.
    network = AccessLimit(allow = []),
    processes = AccessLimit(allow = []),
)
class SlowPipeline : Pipeline {
  override fun run(context: PipelineContext) {
    val label = context.parameters.getValue("label")
    val steps = context.parameters.getValue("steps").toInt()
    val delayMillis = context.parameters.getValue("delayMillis").toLong()
    println("[$label] starting $steps steps, ${delayMillis}ms each")
    for (step in 1..steps) {
      Thread.sleep(delayMillis)
      println("[$label] step $step/$steps done")
      if (step % 10 == 0)
          System.err.println("[$label] checkpoint at step $step (written to stderr)")
    }
    println("[$label] finished")
  }
}
