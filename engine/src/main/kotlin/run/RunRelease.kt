package dev.lawlan.runline.engine.run

import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory

/** How giving back what a run held came out, as far as the end of the run is concerned. */
enum class ReleaseOutcome(val label: String) {
  RELEASED("released"),

  /** It failed; whatever could be given back was. */
  FAILED("failed"),

  /** It had not ended within the release wait; what it holds stays held until it does. */
  TIMED_OUT("timed_out"),
}

/**
 * Gives back what a run holds (its accessors, then its resources, through the [gate]) on a thread
 * of its own, so that no release can hold up the scheduler, and says how it came out within [limit]
 * (ADR-007 "Run 終止時"). A release that fails, or that has not ended within the limit, is logged as
 * an error and counted, once; one that ends after the limit goes on to give back what it held then,
 * which wakes the runs that wait for it.
 */
class RunRelease(
    private val gate: ResourceGate,
    private val limit: Duration,
    private val telemetry: RunTelemetry,
) : AutoCloseable {
  private val log = LoggerFactory.getLogger(RunRelease::class.java)
  private val releasers = Executors.newCachedThreadPool { task ->
    Thread.ofPlatform().name("run-release").daemon(true).unstarted(task)
  }

  /** Starts giving back what [runId] holds; [types] are the types of its typed resources. */
  fun start(runId: UUID, types: Collection<String>): CompletableFuture<ReleaseOutcome> {
    val outcome = CompletableFuture<ReleaseOutcome>()
    // Whichever comes first, the end of the release or the limit, says how it came out; what is
    // logged and counted is done before that is told, so whoever sees the run ended sees it too.
    val claimed = AtomicBoolean()
    val released =
        try {
          CompletableFuture.runAsync({ gate.release(runId) }, releasers)
        } catch (e: RejectedExecutionException) {
          CompletableFuture.failedFuture(e)
        }
    released.whenComplete { _, error ->
      val cause = error?.let(::unwrapped)
      if (claimed.compareAndSet(false, true)) {
        if (cause != null) {
          log.error("Giving back the resources of run {} failed", runId, cause)
          telemetry.releaseFailed(ReleaseOutcome.FAILED, types)
        }
        outcome.complete(if (cause == null) ReleaseOutcome.RELEASED else ReleaseOutcome.FAILED)
      } else if (cause != null) {
        log.error(
            "Giving back the resources of run {} failed, after the release wait",
            runId,
            cause,
        )
      } else {
        log.warn("Run {} gave its resources back, after the release wait", runId)
      }
    }
    CompletableFuture.delayedExecutor(limit.toMillis(), TimeUnit.MILLISECONDS).execute {
      if (claimed.compareAndSet(false, true)) {
        log.error(
            "Run {} has not given its resources back within {}; it is recorded as ended and what " +
                "it holds stays held until the release ends",
            runId,
            limit,
        )
        telemetry.releaseFailed(ReleaseOutcome.TIMED_OUT, types)
        outcome.complete(ReleaseOutcome.TIMED_OUT)
      }
    }
    return outcome
  }

  /** Lets the releases that are still going on finish on their own. */
  override fun close() {
    releasers.shutdown()
  }

  private fun unwrapped(error: Throwable): Throwable =
      if (error is CompletionException || error is ExecutionException) error.cause ?: error
      else error
}
