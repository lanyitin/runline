package dev.lawlan.runline.runner

import java.io.PrintWriter
import java.io.StringWriter
import java.net.URL
import java.time.Duration
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Function
import java.util.function.Supplier

internal enum class StopReason(val status: RunStatus) {
  CANCELLED(RunStatus.CANCELLED),
  TIMED_OUT(RunStatus.TIMED_OUT),
}

/** The lifecycle of one run, executed on the run's own thread. */
internal class RunExecution(
    private val request: RunRequest,
    private val listener: RunListener,
    private val handle: RunHandle,
    private val workspaces: Workspaces,
    private val runtimeClasspath: List<URL>,
    private val timers: ScheduledExecutorService,
    private val unfinishedGrace: Duration,
    private val onLoaderCreated: (ClassLoader) -> Unit,
) {
  private val stop = AtomicReference<StopReason?>(null)
  private val lock = Any()
  private var runThread: Thread? = null
  private val progress = Progress()

  /** Records why the run should stop and interrupts its thread if it is running. */
  fun requestStop(reason: StopReason) {
    stop.compareAndSet(null, reason)
    synchronized(lock) { runThread?.interrupt() }
  }

  /**
   * Runs to a terminal result. Whatever goes wrong outside the pipeline itself (ending the run,
   * releasing its directories, reporting) turns into a failed result, so a result is never left
   * pending and the run's thread, directory bookkeeping and stream attribution are released.
   */
  fun run(): RunResult {
    val thread = Thread.currentThread()
    synchronized(lock) { runThread = thread }
    try {
      return runOn(thread)
    } catch (t: Throwable) {
      return failedUnexpectedly(t)
    } finally {
      synchronized(lock) { runThread = null }
      Thread.interrupted() // the pool thread must not carry this run's interrupt into the next
    }
  }

  private fun runOn(thread: Thread): RunResult {
    stop.get()?.let { reason ->
      publish(reason.status)
      return RunResult(request.runId, null, reason.status, null, emptyList())
    }
    val idleName = thread.name
    thread.name = request.runId
    val loader = RunClassLoader(request.runId, request.jar, runtimeClasspath)
    onLoaderCreated(loader)
    thread.contextClassLoader = loader
    try {
      return execute(loader)
    } finally {
      thread.contextClassLoader = null
      thread.name = idleName
      // Releasing the loader is best effort: failing to close it must not change the run's result.
      runCatching { loader.close() }
    }
  }

  private class Progress(
      var pipelineName: String? = null,
      var prepared: Boolean = false,
      var finishAttempted: Boolean = false,
      var attribution: StandardStreams.Attribution? = null,
  )

  /** The result of a run whose handling itself failed: everything it held is released. */
  private fun failedUnexpectedly(t: Throwable): RunResult {
    val name = progress.pipelineName
    progress.attribution?.let { runCatching { StandardStreams.release(it) } }
    if (progress.prepared && !progress.finishAttempted) {
      progress.finishAttempted = true
      runCatching { workspaces.finish(name!!, request.runId, RunOutcome.FAILED) }
    }
    publish(RunStatus.FAILED)
    val published = handle.status
    return RunResult(
        request.runId,
        name,
        published,
        if (published == RunStatus.FAILED) failureOf(t) else null,
        emptyList(),
    )
  }

  private fun execute(loader: ClassLoader): RunResult {
    publish(RunStatus.INITIALIZING)
    val attribution = StandardStreams.attribute { stream, line ->
      emit(RunEvent.LogLine(request.runId, stream, line))
    }
    progress.attribution = attribution
    val end =
        try {
              initializeAndRun(loader)
            } catch (t: Throwable) {
              End(RunStatus.FAILED, failureOf(t))
            }
            .attributedToStopRequest()
    StandardStreams.release(attribution)
    val residual = residualThreads(loader)
    val name = progress.pipelineName
    if (progress.prepared) {
      progress.finishAttempted = true
      StandardStreams.unattributed {
        workspaces.finish(name!!, request.runId, end.status.outcome())
      }
    }
    publish(end.status)
    return RunResult(request.runId, name, end.status, end.failure, residual, end.recording)
  }

  private fun initializeAndRun(loader: ClassLoader): End {
    val entry = newEntry(loader)
    @Suppress("UNCHECKED_CAST") val describe = entry as Supplier<String>
    @Suppress("UNCHECKED_CAST") val body = entry as Function<Map<String, Any?>, Map<String, Any?>>
    val name = describe.get().also { progress.pipelineName = it }
    val workspace = StandardStreams.unattributed { workspaces.prepare(name, request.runId) }
    progress.prepared = true
    stop.get()?.let {
      return End(it.status, null)
    }
    publish(RunStatus.RUNNING)
    val timer = request.timeout?.let(::startTimeout)
    try {
      val output = body.apply(input(workspace))
      BoundaryTypes.requireJdkOnly(output)
      return endOf(output)
    } finally {
      timer?.cancel(false)
    }
  }

  private fun input(workspace: RunWorkspace): Map<String, Any?> =
      java.util
          .HashMap<String, Any?>()
          .apply {
            put("parameters", java.util.LinkedHashMap(request.parameters))
            put("sharedDir", workspace.sharedDir.toString())
            put("runDir", workspace.runDir.toString())
            put("maxBytesPerScope", workspace.maxBytesPerScope)
            request.recording?.let { put("recordingMaxEvents", it.maxEvents) }
            request.resources?.let { put("resourceTypes", java.util.LinkedHashMap(it.provided)) }
          }
          .also(BoundaryTypes::requireJdkOnly)
          // The one object of the host's own making that enters the run: the call to the host's
          // side of the accessors, seen by the run only as a JDK Function. The host holds nothing
          // of the run, and what crosses the call is checked on every call.
          .also { input -> request.resources?.let { input["resourceCalls"] = HostCall(it) } }

  /**
   * After [timeout] asks the run to stop; if it still has not ended [unfinishedGrace] later, marks
   * it as timed out but unfinished. Cancelling the returned future disarms both.
   */
  private fun startTimeout(timeout: Duration): ScheduledFuture<*> =
      timers.schedule(
          {
            requestStop(StopReason.TIMED_OUT)
            timers.schedule(
                { if (stop.get() == StopReason.TIMED_OUT) publishUnfinished() },
                unfinishedGrace.toMillis(),
                TimeUnit.MILLISECONDS,
            )
          },
          timeout.toMillis(),
          TimeUnit.MILLISECONDS,
      )

  /** A run that fails after being asked to stop ended because of the request. */
  private fun End.attributedToStopRequest(): End {
    val reason = stop.get()
    return if (status == RunStatus.FAILED && reason != null) End(reason.status, failure, recording)
    else this
  }

  /**
   * Live threads that belong to the run's class loader (they inherited it, or their class comes
   * from it) other than the run's own thread. They keep the loader reachable.
   */
  private fun residualThreads(loader: ClassLoader): List<String> =
      Thread.getAllStackTraces()
          .keys
          .filter {
            it !== Thread.currentThread() &&
                it.isAlive &&
                (it.contextClassLoader === loader || it.javaClass.classLoader === loader)
          }
          .map { it.name }

  private fun newEntry(loader: ClassLoader): Any =
      loader
          .loadClass(ENTRY_CLASS)
          .getConstructor(Map::class.java)
          .newInstance(
              java.util.HashMap<String, Any?>().apply {
                put("pipelineClass", request.pipelineClass)
              }
          )

  private class End(
      val status: RunStatus,
      val failure: RunFailure?,
      val recording: RecordedIo? = null,
  )

  @Suppress("UNCHECKED_CAST")
  private fun endOf(output: Map<String, Any?>): End {
    val recording = (output["recording"] as Map<String, Any?>?)?.let(RecordedIo::fromBoundary)
    return if (output["outcome"] == "SUCCEEDED") End(RunStatus.SUCCEEDED, null, recording)
    else
        End(
            RunStatus.FAILED,
            RunFailure(
                output["failureType"] as String,
                output["failureMessage"] as String?,
                output["failureTrace"] as String,
            ),
            recording,
        )
  }

  private fun failureOf(t: Throwable): RunFailure =
      RunFailure(
          t.javaClass.name,
          t.message,
          StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString(),
      )

  private fun RunStatus.outcome(): RunOutcome =
      when (this) {
        RunStatus.SUCCEEDED -> RunOutcome.SUCCEEDED
        RunStatus.CANCELLED -> RunOutcome.CANCELLED
        else -> RunOutcome.FAILED
      }

  /** Statuses are serialized and nothing is published after a terminal one. */
  @Synchronized
  private fun publish(status: RunStatus) {
    if (handle.status.terminal) return
    handle.status = status
    emit(RunEvent.StatusChanged(request.runId, status))
  }

  @Synchronized
  private fun publishUnfinished() {
    if (handle.status == RunStatus.RUNNING) publish(RunStatus.TIMED_OUT_UNFINISHED)
  }

  /** A failing listener must not break the run or its cleanup. */
  private fun emit(event: RunEvent) {
    runCatching { StandardStreams.unattributed { listener.onEvent(event) } }
  }

  private companion object {
    const val ENTRY_CLASS = "dev.lawlan.runline.runner.isolated.RunEntry"
  }
}
