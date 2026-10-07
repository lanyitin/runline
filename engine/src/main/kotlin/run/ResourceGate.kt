package dev.lawlan.runline.engine.run

import dev.lawlan.runline.runner.ResourceHost
import java.util.UUID

/**
 * A run waiting to start, with the shared resources its pipeline declares (ADR-007) and the types
 * it expects some of them to have (ADR-019).
 */
data class PendingRun(
    val id: UUID,
    val pipelineName: String,
    val resources: List<String>,
    val resourceTypes: Map<String, String> = emptyMap(),
)

sealed interface GateDecision {
  /** Everything the run needs is held for it; it may start. */
  data object GRANTED : GateDecision

  /** Something it needs is not available. The run waits without holding a concurrency slot. */
  data object WAIT : GateDecision

  /** The run can never start as it is: it ends as failed with [failure] and holds nothing. */
  data class Refused(val failure: FailureInfo) : GateDecision
}

/**
 * Where shared resources join the start of a run (WI-09). The scheduler asks it for a run only when
 * a concurrency slot is free, so a run is started exactly when the slot and every declared resource
 * are available together; a run that is told to wait holds no slot. The scheduler calls [release]
 * when a run ends, is cancelled while waiting, or fails to start, and [RunScheduler.wake] is how an
 * implementation says that something became available.
 */
interface ResourceGate {
  /**
   * Called on the scheduler's thread; must not wait for other runs (it may read what it needs from
   * storage). Asking again about a run that was told to wait is how a gate learns it is still
   * waiting, so the answer may change from [GateDecision.WAIT] to a grant or a refusal.
   */
  fun tryAcquire(run: PendingRun): GateDecision

  /** Gives back whatever [runId] holds or waits for. Idempotent. */
  fun release(runId: UUID)

  /**
   * Called once by the scheduler that uses this gate; [wake] asks it to look again at the runs that
   * wait, and may be called from any thread. A gate that never needs to say so ignores it.
   */
  fun attach(wake: () -> Unit) = Unit

  /**
   * The accessors prepared for [runId] when it was granted what it declared (ADR-019), or null when
   * it holds no typed resource. [log] receives lines for the run's own log. Called once, when the
   * run is started.
   */
  fun accessors(runId: UUID, log: (String) -> Unit): ResourceHost? = null
}

/**
 * A gate for runs that need no coordination: nothing is ever held and no run waits. The Engine
 * itself uses the ResourceCoordinator of the resource package.
 */
object NoResources : ResourceGate {
  override fun tryAcquire(run: PendingRun) = GateDecision.GRANTED

  override fun release(runId: UUID) = Unit
}
