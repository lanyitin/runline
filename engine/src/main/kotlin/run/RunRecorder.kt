package dev.lawlan.runline.engine.run

import dev.lawlan.runline.runner.RunEvent
import dev.lawlan.runline.runner.RunListener
import dev.lawlan.runline.runner.RunStatus
import java.time.Clock
import java.util.UUID
import org.slf4j.LoggerFactory

/**
 * Receives what the Runner reports about one run and records it: progress through the lifecycle and
 * the log lines. The end of the run is not recorded here; the scheduler records it from the
 * Runner's result, so there is a single writer of the final state.
 */
internal class RunRecorder(
    private val runId: UUID,
    private val progress: RunProgress,
    private val logs: RunLogStore,
    private val clock: Clock,
) : RunListener, AutoCloseable {
  private val log = LoggerFactory.getLogger(RunRecorder::class.java)
  private var appender: LogAppender? = null
  private var logFailureReported = false

  override fun onEvent(event: RunEvent) {
    when (event) {
      is RunEvent.StatusChanged -> event.status.toRunState()?.let { progress.advance(runId, it) }
      is RunEvent.LogLine -> append(event)
    }
  }

  /** A line the Engine adds to the run's log on its own account (a resource, not the pipeline). */
  fun note(line: String) =
      append(RunEvent.LogLine(runId.toString(), dev.lawlan.runline.runner.LogStream.STDOUT, line))

  @Synchronized
  private fun append(event: RunEvent.LogLine) {
    try {
      val target = appender ?: logs.open(runId).also { appender = it }
      target.append(clock.instant(), LogStream.valueOf(event.stream.name), event.line)
    } catch (e: Exception) {
      // A log that cannot be stored must not disturb the run; say so once, not per line.
      if (!logFailureReported) {
        logFailureReported = true
        log.error("Cannot store the log of run {}; further lines may be lost", runId, e)
      }
    }
  }

  @Synchronized
  override fun close() {
    appender?.let { runCatching { it.close() } }
    appender = null
  }

  private fun RunStatus.toRunState(): RunState? =
      when (this) {
        RunStatus.INITIALIZING -> RunState.INITIALIZING
        RunStatus.RUNNING -> RunState.RUNNING
        RunStatus.TIMED_OUT_UNFINISHED -> RunState.TIMED_OUT_UNFINISHED
        else -> null
      }
}
