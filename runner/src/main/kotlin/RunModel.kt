package dev.lawlan.runline.runner

import java.nio.file.Path
import java.time.Duration

/** What to run: a pipeline class inside a jar, with its parameters. */
data class RunRequest(
    /** Unique within the pipeline; also names the run's thread and private directory. */
    val runId: String,
    val jar: Path,
    /** Fully qualified name of the class annotated with `@PipelineDefinition`. */
    val pipelineClass: String,
    val parameters: Map<String, String> = emptyMap(),
    /** Cooperative limit on the pipeline body, counted from the moment it starts. */
    val timeout: Duration? = null,
    /**
     * Records the run's IO instead of enforcing the pipeline's declared limits; for the development
     * entry only. Null, the default, is a normal run.
     */
    val recording: RecordingOptions? = null,
    /** The accessors the host lends for the typed resources the run holds; null for none. */
    val resources: ResourceHost? = null,
)

/** How a recording run keeps what it records; see [dev.lawlan.runline.core.IoRecorder]. */
data class RecordingOptions(val maxEvents: Int = DEFAULT_MAX_EVENTS) {
  init {
    require(maxEvents >= 0) { "maxEvents must not be negative, was $maxEvents" }
  }

  companion object {
    /** About 2 MB at most: far more than anyone reads, and everything beyond it is summarized. */
    const val DEFAULT_MAX_EVENTS = 10_000
  }
}

enum class RunStatus(val terminal: Boolean) {
  /** Before the pipeline body: directories are prepared here (resources join here later). */
  INITIALIZING(false),
  RUNNING(false),
  /** The timeout passed and the run was asked to stop, but it has not ended. It may never. */
  TIMED_OUT_UNFINISHED(false),
  SUCCEEDED(true),
  FAILED(true),
  CANCELLED(true),
  /** The run ended in failure after its timeout had passed. */
  TIMED_OUT(true),
}

enum class LogStream {
  STDOUT,
  STDERR,
}

/** Events pushed to the [RunListener] from the run's thread. */
sealed interface RunEvent {
  val runId: String

  data class StatusChanged(override val runId: String, val status: RunStatus) : RunEvent

  data class LogLine(override val runId: String, val stream: LogStream, val line: String) : RunEvent
}

fun interface RunListener {
  fun onEvent(event: RunEvent)
}

/** A pipeline failure, flattened to strings so nothing from the run's class loader is retained. */
data class RunFailure(val type: String, val message: String?, val trace: String)

data class RunResult(
    val runId: String,
    /** Name from the pipeline's metadata; null when the pipeline could not be loaded. */
    val pipelineName: String?,
    val status: RunStatus,
    val failure: RunFailure?,
    /** Names of threads still alive after the run that belong to its class loader. */
    val residualThreads: List<String>,
    /** The recorded IO of a recording run, if it got far enough to produce one; else null. */
    val recording: RecordedIo? = null,
)
