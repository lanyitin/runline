package dev.lawlan.runline.engine.run

import dev.lawlan.runline.engine.artifact.Visibility
import java.time.Instant
import java.util.UUID

/** Persistence of runs. State changes only move forward (see [RunState]). */
interface RunStore {
  /** Records [run] as queued and returns it as stored. */
  fun insert(run: NewRun): RunRecord

  /** The run, or null when it does not exist or [visibility] does not permit seeing it. */
  fun find(id: UUID, visibility: Visibility): RunRecord?

  /** Newest first. [pipeline] narrows the list to one pipeline name. */
  fun list(visibility: Visibility, pipeline: String?, limit: Int): List<RunRecord>

  /**
   * Moves the run to the non terminal state [to] if that is forward from where it is; returns
   * whether it moved. Entering [RunState.INITIALIZING] also records the start time.
   */
  fun advance(id: UUID, to: RunState, at: Instant): Boolean

  /** Moves the run to the terminal state [to] unless it already ended; returns whether it moved. */
  fun finish(id: UUID, to: RunState, at: Instant, failure: FailureInfo? = null): Boolean

  /** Marks every run that has not ended as interrupted, and says which runs those were. */
  fun interruptUnfinished(at: Instant): List<InterruptedRun>
}

/** Persistence of a run's log. Entries are numbered from 1 and grow without gaps within a run. */
interface RunLogStore {
  fun open(runId: UUID): LogAppender

  /** Entries with a sequence number greater than [afterSeq], oldest first. */
  fun read(runId: UUID, afterSeq: Long, limit: Int): List<LogEntry>
}

/** Appends one run's log lines; closing releases what it holds. */
interface LogAppender : AutoCloseable {
  fun append(at: Instant, stream: LogStream, line: String)
}
