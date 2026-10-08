package dev.lawlan.runline.engine.run

import dev.lawlan.runline.engine.artifact.DefinitionStore
import dev.lawlan.runline.runner.RunHandle
import dev.lawlan.runline.runner.RunRequest
import dev.lawlan.runline.runner.RunResult
import dev.lawlan.runline.runner.RunStatus
import dev.lawlan.runline.runner.Runner
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.slf4j.LoggerFactory

/** What a run needs to be started, known when it is accepted. */
data class RunPlan(
    val id: UUID,
    val contentHash: String,
    val className: String,
    val pipelineName: String,
    val parameters: Map<String, String>,
    val resources: List<String>,
    val resourceTypes: Map<String, String> = emptyMap(),
)

data class SchedulerConfig(
    val maxConcurrentRuns: Int,
    /** Cooperative limit on a pipeline body; none when null. */
    val runTimeout: Duration?,
    /** How long a shutdown waits for runs it asked to stop. */
    val shutdownGrace: Duration,
    /** Where a run's jar is written for as long as it runs. */
    val jarDirectory: Path,
)

enum class CancelOutcome {
  /** The run had not started; it ended as cancelled right away. */
  CANCELLED_BEFORE_START,

  /** The run was asked to stop; it ends when it reacts. */
  CANCELLATION_REQUESTED,

  /** The scheduler holds no such run: it ended already, or never existed. */
  NOT_ACTIVE,
}

/**
 * Decides when accepted runs start and follows them to their end. A run starts when a concurrency
 * slot is free and the [ResourceGate] grants what the run declares, in the order runs were
 * accepted; a run that has to wait for resources holds no slot, so later runs may start before it.
 * A slot is held from the start of a run until the Runner has really finished with it, including a
 * run that outlived its timeout.
 *
 * Every accepted run ends in a terminal state: a failure anywhere on the way to starting it, or in
 * the Runner, becomes a failed run, and the slot, the jar copy, the resources and the log
 * connection are released. All decisions are made on one thread, so there are no races between
 * starting, cancelling and ending; each run's pipeline still runs on its own Runner thread.
 */
class RunScheduler(
    private val runner: Runner,
    private val definitions: DefinitionStore,
    private val progress: RunProgress,
    private val logs: RunLogStore,
    private val gate: ResourceGate,
    private val telemetry: RunTelemetry,
    private val clock: Clock,
    private val config: SchedulerConfig,
) : AutoCloseable {
  private val log = LoggerFactory.getLogger(RunScheduler::class.java)
  private val executor = Executors.newSingleThreadExecutor { task ->
    Thread.ofPlatform().name("run-scheduler").unstarted(task)
  }

  // Confined to the scheduler thread.
  private class Queued(val plan: RunPlan, var waiting: Boolean = false)

  private class Active(val handle: RunHandle, val jar: Path, val recorder: RunRecorder)

  private val queue = ArrayDeque<Queued>()
  private val active = HashMap<UUID, Active>()
  private var closing = false
  private var drained: CompletableFuture<Unit>? = null

  // Read from any thread.
  private val queuedCount = AtomicInteger()
  private val waitingCount = AtomicInteger()
  private val activeCount = AtomicInteger()

  init {
    telemetry.observeScheduler(::stats)
    gate.attach(::wake)
  }

  /** Accepts a run that is already recorded as queued. */
  fun submit(plan: RunPlan) {
    try {
      executor.execute {
        guarded {
          queue.add(Queued(plan))
          dispatch()
        }
      }
    } catch (e: RejectedExecutionException) {
      // Shutting down: the run is not going to start.
      progress.finish(plan.id, RunState.INTERRUPTED)
    }
  }

  /** Asks for the run to stop; the answer says what that meant for the run. */
  fun cancel(id: UUID): CompletableFuture<CancelOutcome> {
    val outcome = CompletableFuture<CancelOutcome>()
    try {
      executor.execute {
        try {
          outcome.complete(cancelOnScheduler(id))
        } catch (t: Throwable) {
          outcome.completeExceptionally(t)
        }
      }
    } catch (e: RejectedExecutionException) {
      outcome.complete(CancelOutcome.NOT_ACTIVE)
    }
    return outcome
  }

  /** Looks again at runs that wait for resources; for a gate to call when something is freed. */
  fun wake() {
    try {
      executor.execute { guarded { dispatch() } }
    } catch (e: RejectedExecutionException) {
      // Shutting down: nothing will start.
    }
  }

  fun stats(): SchedulerStats =
      SchedulerStats(
          queued = queuedCount.get() - waitingCount.get(),
          waiting = waitingCount.get(),
          active = activeCount.get(),
      )

  /**
   * Stops accepting work: runs that have not started are interrupted, running ones are asked to
   * stop and given the configured grace to end. Whatever has not ended by then is recorded as
   * interrupted; it is cut short by the process ending.
   */
  override fun close() {
    val drained = CompletableFuture<Unit>()
    try {
      executor.execute { guarded { beginShutdown(drained) } }
    } catch (e: RejectedExecutionException) {
      return // closed before
    }
    try {
      drained.get(config.shutdownGrace.toMillis(), TimeUnit.MILLISECONDS)
    } catch (e: java.util.concurrent.TimeoutException) {
      log.warn("Some runs did not stop within {}", config.shutdownGrace)
    }
    executor.execute { guarded { abandonRemaining() } }
    executor.shutdown()
    executor.awaitTermination(config.shutdownGrace.toMillis(), TimeUnit.MILLISECONDS)
  }

  // ---- on the scheduler thread ----

  private fun guarded(block: () -> Unit) {
    try {
      block()
    } catch (t: Throwable) {
      log.error("Scheduler step failed", t)
    } finally {
      refreshStats()
    }
  }

  private fun dispatch() {
    while (!closing && active.size < config.maxConcurrentRuns) {
      val next = nextStartable() ?: return
      start(next.plan)
    }
  }

  /** The first queued run, in order, whose resources are granted; others are told to wait. */
  private fun nextStartable(): Queued? {
    for (candidate in queue.toList()) {
      val decision =
          try {
            gate.tryAcquire(
                PendingRun(
                    candidate.plan.id,
                    candidate.plan.pipelineName,
                    candidate.plan.resources,
                    candidate.plan.resourceTypes,
                )
            )
          } catch (t: Throwable) {
            queue.remove(candidate)
            fail(candidate.plan.id, t)
            continue
          }
      when (decision) {
        GateDecision.GRANTED -> {
          queue.remove(candidate)
          return candidate
        }
        GateDecision.WAIT ->
            if (!candidate.waiting) {
              candidate.waiting = true
              refreshStats()
              progress.advance(candidate.plan.id, RunState.WAITING_FOR_RESOURCES)
            }
        is GateDecision.Refused -> {
          queue.remove(candidate)
          refuse(candidate.plan.id, decision.failure)
        }
      }
    }
    return null
  }

  private fun start(plan: RunPlan) {
    var jar: Path? = null
    var recorder: RunRecorder? = null
    try {
      jar = Files.createTempFile(config.jarDirectory, "run-", ".jar")
      check(definitions.copyContent(plan.contentHash, jar)) {
        "The stored jar of version ${plan.contentHash} is missing"
      }
      recorder = RunRecorder(plan.id, progress, logs, clock)
      val handle =
          runner.start(
              RunRequest(
                  plan.id.toString(),
                  jar,
                  plan.className,
                  plan.parameters,
                  config.runTimeout,
                  resources = gate.accessors(plan.id, recorder::note),
              ),
              recorder,
          )
      active[plan.id] = Active(handle, jar, recorder)
      refreshStats()
      handle.result.whenComplete { result, error ->
        try {
          executor.execute { guarded { completed(plan.id, result, error) } }
        } catch (e: RejectedExecutionException) {
          // The scheduler is gone; the run is recorded as interrupted when it shut down.
        }
      }
    } catch (t: Throwable) {
      recorder?.let { runCatching { it.close() } }
      jar?.let { runCatching { Files.deleteIfExists(it) } }
      fail(plan.id, t)
    }
  }

  /** Ends a run that never got started: it is failed and gives back what it was granted. */
  private fun fail(id: UUID, t: Throwable) {
    log.error("Run {} could not be started", id, t)
    runCatching { gate.release(id) }
    runCatching { progress.finish(id, RunState.FAILED, failureOf(t)) }
        .onFailure { log.error("Run {} could not be marked failed", id, it) }
  }

  /** Ends a run the gate says can never start: it is failed with the gate's reason. */
  private fun refuse(id: UUID, failure: FailureInfo) {
    log.warn("Run {} will not start: {}: {}", id, failure.type, failure.message)
    runCatching { gate.release(id) }
    runCatching { progress.finish(id, RunState.FAILED, failure) }
        .onFailure { log.error("Run {} could not be marked failed", id, it) }
    refreshStats()
  }

  private fun completed(id: UUID, result: RunResult?, error: Throwable?) {
    val run = active.remove(id) ?: return
    val ended =
        when {
          error != null -> End(RunState.FAILED, failureOf(error), emptyList())
          else -> End(result!!.stateWhenClosing(), result.failureInfo(), result.residualThreads)
        }
    runCatching { run.recorder.close() }
    // Given back before the end is recorded: whoever sees the run ended sees it holding nothing.
    runCatching { gate.release(id) }
    try {
      progress.finish(id, ended.state, ended.failure, ended.residualThreads)
    } catch (t: Throwable) {
      log.error("Run {} ended {} but that could not be recorded", id, ended.state, t)
    }
    runCatching { Files.deleteIfExists(run.jar) }
    if (closing) {
      if (active.isEmpty()) drained?.complete(Unit)
    } else {
      dispatch()
    }
  }

  private class End(
      val state: RunState,
      val failure: FailureInfo?,
      val residualThreads: List<String>,
  )

  /** A run that stopped because the Engine is shutting down was interrupted, not cancelled. */
  private fun RunResult.stateWhenClosing(): RunState =
      when (status) {
        RunStatus.SUCCEEDED -> RunState.SUCCEEDED
        RunStatus.CANCELLED -> if (closing) RunState.INTERRUPTED else RunState.CANCELLED
        RunStatus.TIMED_OUT -> RunState.TIMED_OUT
        else -> RunState.FAILED
      }

  private fun RunResult.failureInfo(): FailureInfo? = failure?.let {
    FailureInfo(it.type, it.message, it.trace)
  }

  private fun cancelOnScheduler(id: UUID): CancelOutcome {
    val waiting = queue.firstOrNull { it.plan.id == id }
    if (waiting != null) {
      queue.remove(waiting)
      runCatching { gate.release(id) }
      progress.finish(id, RunState.CANCELLED)
      refreshStats()
      dispatch() // what it waited for may now be free for another run
      return CancelOutcome.CANCELLED_BEFORE_START
    }
    val running = active[id] ?: return CancelOutcome.NOT_ACTIVE
    running.handle.cancel()
    return CancelOutcome.CANCELLATION_REQUESTED
  }

  private fun beginShutdown(drained: CompletableFuture<Unit>) {
    closing = true
    this.drained = drained
    for (queued in queue.toList()) {
      runCatching { gate.release(queued.plan.id) }
      runCatching { progress.finish(queued.plan.id, RunState.INTERRUPTED) }
    }
    queue.clear()
    active.values.forEach { it.handle.cancel() }
    if (active.isEmpty()) drained.complete(Unit)
  }

  private fun abandonRemaining() {
    for ((id, run) in active) {
      runCatching { run.recorder.close() }
      runCatching { gate.release(id) }
      runCatching { progress.finish(id, RunState.INTERRUPTED) }
    }
    active.clear()
  }

  private fun refreshStats() {
    queuedCount.set(queue.size)
    waitingCount.set(queue.count { it.waiting })
    activeCount.set(active.size)
  }

  private fun failureOf(t: Throwable) =
      FailureInfo(
          t.javaClass.name,
          t.message,
          StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString(),
      )
}
