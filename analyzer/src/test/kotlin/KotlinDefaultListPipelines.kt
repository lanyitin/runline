@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package demo.defaults

import dev.lawlan.runline.core.AccessLimit
import dev.lawlan.runline.core.FileAccess
import dev.lawlan.runline.core.FileMode
import dev.lawlan.runline.core.FileScope
import dev.lawlan.runline.core.Param
import dev.lawlan.runline.core.Pipeline
import dev.lawlan.runline.core.PipelineContext
import dev.lawlan.runline.core.PipelineDefinition
import java.io.Closeable
import java.io.File
import java.io.PrintStream
import java.net.Socket
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.experimental.and
import kotlin.math.roundToInt
import kotlin.streams.asSequence

/*
 * Compiled by the Kotlin compiler as test input for the default allow list (WI-10). They are only
 * ever read as bytes; the tests never load or run them. The safe ones are written the way authors
 * write pipelines; the unsafe ones use files, the network or processes directly.
 */

/** A typical pipeline: sequential steps, context, collections and strings, print, read stdin. */
@PipelineDefinition(
    name = "defaults-typical",
    parameters = [Param(name = "target", required = false, default = "world")],
    files = [FileAccess(scope = FileScope.RUN_PRIVATE, mode = FileMode.READ_WRITE)],
    network = AccessLimit(allow = []),
    processes = AccessLimit(allow = []),
)
class DefaultsTypicalPipeline : Pipeline {
  private enum class Level {
    LOW,
    HIGH,
  }

  private data class Item(val name: String, val level: Level)

  override fun run(context: PipelineContext) {
    val target = context.parameters["target"] ?: "world"
    context.files.writeText(FileScope.RUN_PRIVATE, "greeting.txt", "hello $target")

    val items = listOf("b", "a", "c").map { Item(it, if (it == "a") Level.HIGH else Level.LOW) }
    val byLevel = items.sortedBy { it.name }.groupBy { it.level }.mapValues { (_, v) -> v.size }
    val text = items.joinToString(", ") { it.name.uppercase() }.trim().padStart(12)
    val odd = (1..5).map { it * it }.filter { it % 2 == 1 }.sum()
    val counts = mutableMapOf<String, Int>()
    "a-b-a".split("-").forEach { counts[it] = (counts[it] ?: 0) + 1 }
    println("target=$target text=$text byLevel=$byLevel odd=$odd counts=$counts")
    print("no newline")

    val first = readLine()
    val second = readln()
    println("$first $second ${"%d".format(odd)}")
    runCatching { check(odd > 0) { "positive" } }.onFailure { println(it.message) }
  }
}

/** Reads input and prints only, nothing else. */
@PipelineDefinition(
    name = "defaults-stdio",
    network = AccessLimit(allow = []),
    processes = AccessLimit(allow = []),
)
class DefaultsStdioPipeline : Pipeline {
  override fun run(context: PipelineContext) {
    println(readln())
  }
}

/** Uses the Kotlin standard library packages the default list names one by one. */
@PipelineDefinition(
    name = "defaults-kotlin-packages",
    network = AccessLimit(allow = []),
    processes = AccessLimit(allow = []),
)
class DefaultsKotlinPackagesPipeline : Pipeline {
  @Target(AnnotationTarget.FUNCTION)
  @Retention(AnnotationRetention.RUNTIME)
  private annotation class Marker

  private enum class Mode {
    ON,
    OFF,
  }

  private var counter: Int by kotlin.properties.Delegates.notNull()

  @Marker
  override fun run(context: PipelineContext) {
    counter = 1
    val sorted = listOf("bb", "a").sortedWith(compareBy { it.length })
    var stepped = 0
    for (i in context.parameters.size + 10 downTo 0 step 3) stepped += i
    val range = (10 downTo 1 step 3).toList()
    val rounded = kotlin.math.sqrt(2.0).roundToInt()
    val random = kotlin.random.Random.nextInt(10)
    val type = String::class
    val sequence = sequence {
      yield(1)
      yield(2)
    }
    val duration = 5.seconds
    val id = kotlin.uuid.Uuid.random()
    val modes = Mode.entries
    val worker = kotlin.concurrent.thread(start = false) { counter++ }
    val stream = java.util.stream.Stream.of(1, 2).asSequence().toList()
    val closeable = AutoCloseable { counter++ }
    val timed = kotlin.system.measureTimeMillis { counter++ }
    val bits = (1.toByte() and 3.toByte()).toInt()
    val pair = 1 to "one"
    val lazy by lazy { 1 }
    val bytes = "text".toByteArray()
    println("$sorted $range $rounded $random $type ${sequence.toList()} $duration $id $modes")
    println(
        "$stepped $closeable $worker $stream $timed $bits $pair $lazy ${bytes.size} ${Regex("a+").matches("aa")}"
    )
    kotlin.io.println(kotlin.collections.emptyList<String>())
  }

  private val Int.seconds
    get() = kotlin.time.Duration.parse("${this}s")
}

/** Closes a resource that is not a file with `use`, which compiles to `kotlin.io.CloseableKt`. */
@PipelineDefinition(
    name = "defaults-use-resource",
    network = AccessLimit(allow = []),
    processes = AccessLimit(allow = []),
)
class DefaultsUseResourcePipeline : Pipeline {
  private class Counter : Closeable {
    var closed = 0

    override fun close() {
      closed++
    }
  }

  override fun run(context: PipelineContext) {
    val counter = Counter()
    val result = counter.use { "used" }
    println("$result ${counter.closed}")
  }
}

/** Opens a file with the Kotlin file extensions and closes it with `use`. */
@PipelineDefinition(
    name = "defaults-use-file",
    network = AccessLimit(allow = []),
    processes = AccessLimit(allow = []),
)
class DefaultsUseFilePipeline : Pipeline {
  override fun run(context: PipelineContext) {
    println(File("in.txt").bufferedReader().use { it.readLine() })
  }
}

/** Opens files with the Kotlin file extensions. */
@PipelineDefinition(
    name = "defaults-file-kotlin",
    network = AccessLimit(allow = []),
    processes = AccessLimit(allow = []),
)
class DefaultsFileKotlinPipeline : Pipeline {
  override fun run(context: PipelineContext) {
    println(File("in.txt").readText())
    File("out.txt").writeText("data")
  }
}

/** Opens files with the JDK. */
@PipelineDefinition(
    name = "defaults-file-jdk",
    network = AccessLimit(allow = []),
    processes = AccessLimit(allow = []),
)
class DefaultsFileJdkPipeline : Pipeline {
  override fun run(context: PipelineContext) {
    println(Files.readAllLines(Paths.get("in.txt")))
  }
}

/** Opens a file through the standard output class. */
@PipelineDefinition(
    name = "defaults-file-print-stream",
    network = AccessLimit(allow = []),
    processes = AccessLimit(allow = []),
)
class DefaultsFilePrintStreamPipeline : Pipeline {
  override fun run(context: PipelineContext) {
    println("hello")
    PrintStream("out.txt").use { it.println("data") }
  }
}

/** Connects to the network with the JDK. */
@PipelineDefinition(
    name = "defaults-network",
    network = AccessLimit(allow = []),
    processes = AccessLimit(allow = []),
)
class DefaultsNetworkPipeline : Pipeline {
  override fun run(context: PipelineContext) {
    println(URL("http://example.com").readText())
    Socket("example.com", 80).close()
  }
}

/** Starts an external process with the JDK, in a package the default list trusts. */
@PipelineDefinition(
    name = "defaults-process",
    network = AccessLimit(allow = []),
    processes = AccessLimit(allow = []),
)
class DefaultsProcessPipeline : Pipeline {
  override fun run(context: PipelineContext) {
    ProcessBuilder("ls").start()
    Runtime.getRuntime().exec("ls")
  }
}

/** Reads standard input straight from the JDK. */
@PipelineDefinition(
    name = "defaults-jdk-input",
    network = AccessLimit(allow = []),
    processes = AccessLimit(allow = []),
)
class DefaultsJdkInputPipeline : Pipeline {
  override fun run(context: PipelineContext) {
    println(System.`in`.bufferedReader().readLine())
  }
}
