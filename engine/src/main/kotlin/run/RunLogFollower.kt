package dev.lawlan.runline.engine.run

import dev.lawlan.runline.engine.artifact.Visibility
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Follows a run's log as it grows, until the run has ended and everything it wrote was delivered.
 * It reads the stored log, so nothing is kept in memory per follower, a slow follower only falls
 * behind, and one that leaves has no effect on the run.
 */
class RunLogFollower(private val catalog: RunCatalog, private val pollInterval: Duration) {
  /**
   * Calls [deliver] for each entry after [afterSeq], in order. Returns false, delivering nothing,
   * when the run does not exist or [visibility] does not permit seeing it.
   */
  suspend fun follow(
      id: UUID,
      visibility: Visibility,
      afterSeq: Long,
      deliver: suspend (LogEntry) -> Unit,
  ): Boolean {
    var last = afterSeq
    var seen = false
    while (true) {
      // The state is read before the log: once a run is seen ended, one more read has all of it.
      val run = withContext(Dispatchers.IO) { catalog.find(id, visibility) } ?: return seen
      seen = true
      val entries =
          withContext(Dispatchers.IO) { catalog.log(id, visibility, last, BATCH) }.orEmpty()
      entries.forEach { deliver(it) }
      last = entries.lastOrNull()?.seq ?: last
      if (entries.isNotEmpty()) continue
      if (run.state.terminal) return true
      delay(pollInterval.toMillis())
    }
  }

  private companion object {
    const val BATCH = 500
  }
}
