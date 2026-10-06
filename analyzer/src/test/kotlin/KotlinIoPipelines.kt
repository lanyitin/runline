package demo

import dev.lawlan.runline.core.AccessLimit
import dev.lawlan.runline.core.Pipeline
import dev.lawlan.runline.core.PipelineContext
import dev.lawlan.runline.core.PipelineDefinition
import java.util.Formatter
import java.util.Scanner

/*
 * Compiled by the Kotlin compiler as test input for IO sensitive member detection (WI-21). They are
 * only ever read as bytes; the tests never load or run them.
 */

/** Direct calls and constructions written the way authors write them. */
@PipelineDefinition(
    name = "kotlin-io-direct",
    network = AccessLimit(allow = []),
    processes = AccessLimit(allow = []),
)
class KotlinIoDirectPipeline : Pipeline {
  override fun run(context: PipelineContext) {
    ProcessBuilder("ls").start()
    Runtime.getRuntime().exec("ls")
    System.loadLibrary("x")
    Formatter("out.txt")
  }
}

/** Callable references, which Kotlin may compile into synthetic classes. */
@PipelineDefinition(
    name = "kotlin-io-references",
    network = AccessLimit(allow = []),
    processes = AccessLimit(allow = []),
)
class KotlinIoReferencesPipeline : Pipeline {
  override fun run(context: PipelineContext) {
    val start = ProcessBuilder("ls")::start
    val exec: (String) -> Process = Runtime.getRuntime()::exec
    val load: (String) -> Unit = System::loadLibrary
    val open: (String) -> Formatter = ::Formatter
    listOf(start, exec, load, open)
  }
}

/** Lambdas, delegation and an inline function that call IO sensitive members. */
@PipelineDefinition(
    name = "kotlin-io-generated",
    network = AccessLimit(allow = []),
    processes = AccessLimit(allow = []),
)
class KotlinIoGeneratedPipeline : Pipeline {
  private val launcher by lazy { ProcessBuilder("ls").start() }

  private inline fun starting(block: () -> Process) = block()

  override fun run(context: PipelineContext) {
    listOf("a").forEach { Runtime.getRuntime().exec(it) }
    starting { ProcessBuilder("ls").start() }
    launcher
  }
}

/** Memory-only forms of the same classes: must stay safe. */
@PipelineDefinition(
    name = "kotlin-io-memory",
    network = AccessLimit(allow = []),
    processes = AccessLimit(allow = []),
)
class KotlinIoMemoryPipeline : Pipeline {
  override fun run(context: PipelineContext) {
    Scanner("1 2 3").nextInt()
    Formatter().format("%d", 1)
    ProcessBuilder("ls").command()
  }
}
