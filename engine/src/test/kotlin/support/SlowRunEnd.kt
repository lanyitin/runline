package dev.lawlan.runline.engine.support

import dev.lawlan.runline.engine.run.GateDecision
import dev.lawlan.runline.engine.run.PendingRun
import dev.lawlan.runline.engine.run.ResourceGate
import dev.lawlan.runline.runner.ResourceHost
import java.time.Duration
import java.util.UUID

/**
 * The real gate of a test, with time put between the steps of a run's end (WI-59): [beforeRelease]
 * before the run's accessors are stopped and its resources given back, [afterRelease] between that
 * and whatever the scheduler does next (recording the end). Everything else is the gate's own. It
 * makes a window that load may open by chance wide enough to be seen every time; it exists only in
 * tests, the Engine has no such pause.
 */
class SlowRunEnd(
    private val gate: ResourceGate,
    private val beforeRelease: Duration,
    private val afterRelease: Duration,
) : ResourceGate {
  override fun tryAcquire(run: PendingRun): GateDecision = gate.tryAcquire(run)

  override fun release(runId: UUID) {
    Thread.sleep(beforeRelease)
    try {
      gate.release(runId)
    } finally {
      Thread.sleep(afterRelease)
    }
  }

  override fun attach(wake: () -> Unit) = gate.attach(wake)

  override fun accessors(runId: UUID, log: (String) -> Unit): ResourceHost? =
      gate.accessors(runId, log)
}
