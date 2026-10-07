package dev.lawlan.runline.engine.run

import java.time.Instant
import java.util.UUID

/** What caused a run: a person calling the API, or a trigger (WI-07). */
sealed interface RunSource {
  val kind: String
  val name: String

  /** [name] is the caller's name (the name of the token, never the token). */
  data class Manual(override val name: String) : RunSource {
    override val kind = MANUAL
  }

  data class Trigger(override val name: String) : RunSource {
    override val kind = TRIGGER
  }

  companion object {
    const val MANUAL = "MANUAL"
    const val TRIGGER = "TRIGGER"

    fun of(kind: String, name: String): RunSource =
        when (kind) {
          MANUAL -> Manual(name)
          TRIGGER -> Trigger(name)
          else -> error("Unknown run source kind '$kind'")
        }
  }
}

/** The unsafe-execution setting in force when an unsafe pipeline was started (ADR-006). */
data class UnsafeExecution(val setBy: String, val setAt: Instant)

/** A failure flattened to text, as the Runner reports it. */
data class FailureInfo(val type: String, val message: String?, val trace: String)

/** A run to be recorded as [RunState.QUEUED]. */
data class NewRun(
    val id: UUID,
    val definitionId: Long,
    val source: RunSource,
    /** The parameters the run receives: supplied values plus defaults. */
    val parameters: Map<String, String>,
    val createdAt: Instant,
    /** Present only when an unsafe pipeline runs with permission; the setting at creation time. */
    val unsafeExecution: UnsafeExecution?,
)

data class RunRecord(
    val id: UUID,
    val contentHash: String,
    /** Whose version of the content the run is of (ADR-020). */
    val uploader: String,
    val className: String,
    val pipelineName: String,
    val state: RunState,
    val source: RunSource,
    val parameters: Map<String, String>,
    val createdAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val failure: FailureInfo?,
    val unsafeExecution: UnsafeExecution?,
)

enum class LogStream {
  STDOUT,
  STDERR,
}

data class LogEntry(val seq: Long, val at: Instant, val stream: LogStream, val line: String)

/** A run that was cut short by an Engine restart, with what is needed to release its directory. */
data class InterruptedRun(val id: UUID, val pipelineName: String)
