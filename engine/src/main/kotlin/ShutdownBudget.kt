package dev.lawlan.runline.engine

import java.time.Duration

/**
 * The grace time of a shutdown, as one budget for the whole of it (04 "優雅關閉"): it starts when the
 * Engine is told to stop, and each step (the requests in flight, the runs and what they give back,
 * the closing of OpenTelemetry) waits at most for what is left, so the steps never add up to more
 * than the grace time. What has to be done once it is spent (recording runs as interrupted, letting
 * go of connections) is the wrap-up, which 04 bounds by [WRAP_UP].
 */
class ShutdownBudget(
    private val grace: Duration,
    private val nanoTime: () -> Long = System::nanoTime,
) {
  private var deadline: Long? = null

  /** The shutdown has begun; starting again changes nothing. */
  @Synchronized
  fun start() {
    if (deadline == null) deadline = nanoTime() + grace.toNanos()
  }

  /** What is left of the grace time; asking before [start] begins the shutdown. */
  @Synchronized
  fun remaining(): Duration {
    start()
    return Duration.ofNanos(maxOf(0L, deadline!! - nanoTime()))
  }

  companion object {
    /**
     * The most a shutdown takes beyond the grace time (04 "優雅關閉"): recording the runs that did not
     * stop as interrupted, closing connections and the server, leaving the process.
     */
    val WRAP_UP: Duration = Duration.ofSeconds(10)
  }
}
