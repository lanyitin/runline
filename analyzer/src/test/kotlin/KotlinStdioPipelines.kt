package demo.stdio

import dev.lawlan.runline.core.AccessLimit
import dev.lawlan.runline.core.Pipeline
import dev.lawlan.runline.core.PipelineContext
import dev.lawlan.runline.core.PipelineDefinition
import java.io.File
import java.io.FileInputStream
import java.io.PrintStream

/*
 * Compiled by the Kotlin compiler as test input for class level allow list entries (WI-24). They
 * are only ever read as bytes; the tests never load or run them.
 */

/** The typical pipeline that prints text: `println` is inline and compiles to `System.out`. */
@PipelineDefinition(
    name = "stdio-print",
    network = AccessLimit(allow = []),
    processes = AccessLimit(allow = []),
)
class StdioPrintPipeline : Pipeline {
  override fun run(context: PipelineContext) {
    println("hello")
    print(1)
    System.out.println("again")
    System.err.println("problem")
  }
}

/** Reads a line from standard input the Kotlin way. */
@PipelineDefinition(
    name = "stdio-read-line",
    network = AccessLimit(allow = []),
    processes = AccessLimit(allow = []),
)
class StdioReadLinePipeline : Pipeline {
  override fun run(context: PipelineContext) {
    val line = readLine()
    val required = readln()
    println(line + required)
  }
}

/** Reads standard input with the JDK. */
@PipelineDefinition(
    name = "stdio-jdk-input",
    network = AccessLimit(allow = []),
    processes = AccessLimit(allow = []),
)
class StdioJdkInputPipeline : Pipeline {
  override fun run(context: PipelineContext) {
    val scanner = java.util.Scanner(System.`in`)
    val reader = System.`in`.bufferedReader()
    println(scanner.nextLine() + reader.readLine())
  }
}

/** Opens a file by name through the standard output class. */
@PipelineDefinition(
    name = "stdio-opens-file",
    network = AccessLimit(allow = []),
    processes = AccessLimit(allow = []),
)
class StdioOpensFilePipeline : Pipeline {
  override fun run(context: PipelineContext) {
    println("hello")
    PrintStream("out.txt").use { it.println("data") }
  }
}

/** Uses other file classes of the standard output class's package. */
@PipelineDefinition(
    name = "stdio-file-classes",
    network = AccessLimit(allow = []),
    processes = AccessLimit(allow = []),
)
class StdioFileClassesPipeline : Pipeline {
  override fun run(context: PipelineContext) {
    println("hello")
    File("in.txt").exists()
    FileInputStream("in.txt").close()
  }
}
