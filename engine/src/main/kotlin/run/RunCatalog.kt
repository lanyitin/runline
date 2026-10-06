package dev.lawlan.runline.engine.run

import dev.lawlan.runline.engine.artifact.Visibility
import java.util.UUID

/** Queries over runs and their logs, always within what the caller may see. */
class RunCatalog(private val runs: RunStore, private val logs: RunLogStore) {
  fun find(id: UUID, visibility: Visibility): RunRecord? = runs.find(id, visibility)

  fun list(visibility: Visibility, pipeline: String?, limit: Int): List<RunRecord> =
      runs.list(visibility, pipeline, limit)

  /** Log entries after [afterSeq]; null when the run does not exist or is not visible. */
  fun log(id: UUID, visibility: Visibility, afterSeq: Long, limit: Int): List<LogEntry>? =
      runs.find(id, visibility)?.let { logs.read(id, afterSeq, limit) }
}
