package dev.lawlan.runline.runner

import java.net.URL
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Class loader accounting for leak monitoring: [created] minus [reclaimed] are still reachable. */
data class LoaderStats(val created: Long, val reclaimed: Long)

class RunHandle
internal constructor(
    val runId: String,
    val result: CompletableFuture<RunResult>,
    private val onCancel: () -> Unit,
) {
  @Volatile
  var status: RunStatus = RunStatus.INITIALIZING
    internal set

  /**
   * Asks the run to stop by interrupting its thread; calls that can be woken are woken.
   * Cooperative: a run that does not react keeps running. A run that has not started never will;
   * one that has ended is unaffected.
   */
  fun cancel() = onCancel()
}

/**
 * Executes pipelines, each in its own class loader on its own platform thread. At most
 * [maxConcurrentRuns] run at once; further runs wait for a free thread.
 */
class Runner(
    private val workspaces: Workspaces,
    maxConcurrentRuns: Int,
    private val unfinishedGrace: Duration = Duration.ofSeconds(5),
    private val runtimeClasspath: List<URL> = RunClassLoader.defaultRuntimeClasspath(),
) : AutoCloseable {
  private val executor =
      ThreadPoolExecutor(
          maxConcurrentRuns,
          maxConcurrentRuns,
          0L,
          TimeUnit.MILLISECONDS,
          LinkedBlockingQueue(),
          { task -> Thread.ofPlatform().name("runner-idle").unstarted(task) },
      )

  private val loaders = LoaderTracker()

  /** The timeouts of runs; the first run that has one makes its thread (WI-66). */
  private val timers = ScheduledThreadPoolExecutor(1, SharedThreads.factory("runner-timers"))

  fun start(request: RunRequest, listener: RunListener): RunHandle {
    val future = CompletableFuture<RunResult>()
    lateinit var execution: RunExecution
    val handle = RunHandle(request.runId, future) { execution.requestStop(StopReason.CANCELLED) }
    execution =
        RunExecution(
            request,
            listener,
            handle,
            workspaces,
            runtimeClasspath,
            timers,
            unfinishedGrace,
            loaders::track,
        )
    executor.execute {
      // Last resort: even an error that escapes the run's own handling completes the result.
      try {
        future.complete(execution.run())
      } catch (t: Throwable) {
        future.completeExceptionally(t)
      }
    }
    return handle
  }

  /** How many run class loaders were created and how many of them have since been reclaimed. */
  fun loaderStats(): LoaderStats = loaders.stats()

  override fun close() {
    executor.shutdown()
    timers.shutdown()
  }
}
