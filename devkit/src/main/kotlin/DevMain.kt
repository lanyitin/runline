package dev.lawlan.runline.devkit

import java.io.PrintStream
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ThreadLocalRandom
import kotlin.system.exitProcess

/** Entry point of the development run/debug configuration. */
object DevMain {
  private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

  /** Parses [args] and [env], runs the pipeline and returns the exit code. */
  fun run(args: List<String>, env: Map<String, String>, projectDir: Path, out: PrintStream): Int {
    val arguments: DevArguments
    val config: DevConfig
    try {
      arguments = DevArguments.parse(args)
      config = DevConfig.fromEnvironment(env, projectDir)
    } catch (e: IllegalArgumentException) {
      out.println(e.message)
      return DevSession.EXIT_NOT_STARTED
    } catch (e: IllegalStateException) {
      out.println(e.message)
      return DevSession.EXIT_NOT_STARTED
    }
    return DevSession(config, out).execute(arguments, newRunId())
  }

  /** Unique per execution, so each run gets a fresh private directory and its own thread name. */
  private fun newRunId(): String =
      "dev-${LocalDateTime.now().format(STAMP)}-%04x"
          .format(ThreadLocalRandom.current().nextInt(0x10000))
}

/**
 * Usage: `<pipeline-jar> <pipeline-class> [name=value ...]`, run with the development project as
 * the working directory. The output stream is captured here, before the Runner redirects the JVM's
 * standard streams for a run.
 */
fun main(args: Array<String>) {
  val code = DevMain.run(args.toList(), System.getenv(), Path.of("").toAbsolutePath(), System.out)
  exitProcess(code)
}
