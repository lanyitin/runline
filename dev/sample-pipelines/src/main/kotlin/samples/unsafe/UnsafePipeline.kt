package samples.unsafe

import dev.lawlan.runline.core.Pipeline
import dev.lawlan.runline.core.PipelineContext
import dev.lawlan.runline.core.PipelineDefinition
import java.io.File

/**
 * Judged UNSAFE twice over: network and processes are left unrestricted, and java.io.File is not on
 * the allow list. A run is refused (409 unsafe_not_allowed) until an administrator allows unsafe
 * execution for this version; it then does nothing harmful.
 */
@PipelineDefinition(name = "demo-unsafe")
class UnsafePipeline : Pipeline {
  override fun run(context: PipelineContext) {
    val tmp = File(System.getProperty("java.io.tmpdir"))
    println("the temporary directory exists: ${tmp.exists()}")
  }
}
