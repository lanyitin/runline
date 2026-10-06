package dev.lawlan.runline.engine.run

/**
 * Where a run is in its life (06-data-model). It only ever moves forward, never back: the
 * declaration order is the order of the lifecycle, and every terminal state ends it.
 */
enum class RunState(val terminal: Boolean) {
  QUEUED(false),
  WAITING_FOR_RESOURCES(false),
  INITIALIZING(false),
  RUNNING(false),
  TIMED_OUT_UNFINISHED(false),
  SUCCEEDED(true),
  FAILED(true),
  CANCELLED(true),
  INTERRUPTED(true),
  TIMED_OUT(true);

  /** Whether a run in this state may move to [next]. */
  fun canAdvanceTo(next: RunState): Boolean = !terminal && (next.terminal || next.ordinal > ordinal)
}
