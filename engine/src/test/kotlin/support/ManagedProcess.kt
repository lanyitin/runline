package dev.lawlan.runline.engine.support

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * A real process started by a test, with its output in a file. Waiting for it is limited and a
 * failure says what was waited for and what the process had written; closing it ends it whatever
 * state it is in, so a failed, interrupted or timed-out test leaves nothing running.
 */
class ManagedProcess
private constructor(
    val description: String,
    val process: Process,
    private val log: Path,
    private val terminationGrace: Duration,
) : AutoCloseable {
  init {
    live += process
  }

  /**
   * Waits for the process to end and returns its exit code; fails if it has not ended in [timeout].
   */
  fun awaitExit(timeout: Duration = TestTimeouts.processExit): Int {
    if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
      throw AssertionError(
          "$description (pid ${process.pid()}) did not end within ${timeout.toMillis()} ms; " +
              "its output ends:\n${outputTail()}"
      )
    }
    return process.exitValue()
  }

  fun output(): String = Files.readString(log)

  /** The last [lines] lines the process wrote, for the message of a failure. */
  fun outputTail(lines: Int = 40): String =
      output().lines().dropLastWhile { it.isEmpty() }.takeLast(lines).joinToString("\n")

  /** Asks the process to end (SIGTERM), as a platform stops a container. */
  fun terminate() {
    process.destroy()
  }

  /**
   * Asks the process to end and kills it if it has not within the grace time. Works on a thread
   * that has been interrupted (a test that times out is interrupted before its clean-up runs); the
   * interruption is passed on afterwards.
   */
  override fun close() {
    var interrupted = Thread.interrupted()

    /** True if the process is gone; false if the time passed or this thread was interrupted. */
    fun gone(timeout: Duration): Boolean =
        try {
          process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
          interrupted = true
          false
        }

    try {
      if (process.isAlive) {
        process.destroy()
        if (!gone(terminationGrace)) {
          process.destroyForcibly()
          gone(TestTimeouts.processKill)
        }
      }
    } finally {
      live -= process
      if (interrupted) Thread.currentThread().interrupt()
    }
  }

  companion object {
    /**
     * Processes still running when the test JVM ends, for a JVM that ends without the tests'
     * clean-up (a build that is stopped, a test task that is cut off by its time limit).
     */
    private val live: MutableSet<Process> = ConcurrentHashMap.newKeySet()

    init {
      Runtime.getRuntime().addShutdownHook(Thread { live.forEach { it.destroyForcibly() } })
    }

    fun start(
        description: String,
        command: List<String>,
        log: Path,
        environment: Map<String, String> = emptyMap(),
        directory: Path? = null,
        terminationGrace: Duration = TestTimeouts.processTerminate,
    ): ManagedProcess =
        ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .also { b ->
              directory?.let { b.directory(it.toFile()) }
              b.environment().putAll(environment)
            }
            .start()
            .let { ManagedProcess(description, it, log, terminationGrace) }
  }
}
