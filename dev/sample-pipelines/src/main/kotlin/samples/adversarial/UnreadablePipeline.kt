package samples.adversarial

import dev.lawlan.runline.core.Pipeline
import dev.lawlan.runline.core.PipelineContext
import dev.lawlan.runline.core.PipelineDefinition

/**
 * For the security check of the Console (WI-37): the jar of this one has, in place of the class
 * this pipeline calls, bytes that are no class file and carry markup, so the verdict has reasons
 * that carry strings from inside the jar.
 */
@PipelineDefinition(name = "adversarial-unreadable")
class UnreadablePipeline : Pipeline {
  override fun run(context: PipelineContext) =
      println(`UnreadableHelper id="pwn-class" onerror=pwned(1)`.greeting())
}

/**
 * Compiled for real, then replaced in the jar by an entry that is not a class file. Its name is
 * made of what markup is made of (quotes, `=`, `&`), as far as the JVM lets a class name be.
 */
object `UnreadableHelper id="pwn-class" onerror=pwned(1)` {
  fun greeting() = "never run"
}
