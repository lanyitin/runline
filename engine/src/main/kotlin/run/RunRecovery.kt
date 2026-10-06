package dev.lawlan.runline.engine.run

import dev.lawlan.runline.runner.RunOutcome
import dev.lawlan.runline.runner.Workspaces
import java.time.Clock
import org.slf4j.LoggerFactory

/**
 * Settles runs left unfinished by a previous Engine process (06-data-model, 04-deployment). Such a
 * run is marked interrupted and is never started again. Its private directory is handled by the
 * ordinary retention rule for runs that did not succeed, counted from now: the Runner's outcome for
 * a run that ended in failure and for one that was interrupted is treated alike by the directories,
 * so no outcome of the Runner needs to change.
 */
class RunRecovery(
    private val runs: RunStore,
    private val workspaces: Workspaces,
    private val clock: Clock,
) {
  private val log = LoggerFactory.getLogger(RunRecovery::class.java)

  /** Returns how many runs were marked interrupted. */
  fun recover(): Int {
    val interrupted = runs.interruptUnfinished(clock.instant())
    for (run in interrupted) {
      try {
        workspaces.finish(run.pipelineName, run.id.toString(), RunOutcome.INTERRUPTED)
      } catch (e: Exception) {
        // The run is settled either way; the directory is left to the periodic sweep.
        log.warn("Cannot settle the directory of interrupted run {}", run.id, e)
      }
    }
    if (interrupted.isNotEmpty()) {
      log.warn("Marked {} unfinished run(s) of a previous process as interrupted", interrupted.size)
    }
    return interrupted.size
  }
}
