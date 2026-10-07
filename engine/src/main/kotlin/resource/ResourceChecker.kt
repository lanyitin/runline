package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.engine.auth.ApiIdentity
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.slf4j.LoggerFactory

sealed interface CheckOutcome {
  data object NotFound : CheckOutcome

  data class Done(val result: CheckResult) : CheckOutcome
}

/**
 * Looks at the real entity behind a resource when an administrator asks (ADR-019): it takes no
 * capacity and touches no run, holder or waiter. The answer is a pass or a category of failure,
 * never a reason in words, a path or an address; the details go to the Engine's log. It is kept on
 * the resource with its time, as long as the resource's settings are those that were checked.
 *
 * A check may block (a named pipe nobody is at the other end of blocks an open for ever), so it
 * runs on a thread of its own and the caller waits for at most [timeout]; beyond it the answer is a
 * timeout. A thread the JDK cannot interrupt stays blocked, which is why a resource has at most one
 * check at a time: checks at the same time share the one answer, and while a check that timed out
 * is still blocked, asking again answers a timeout at once and starts nothing.
 */
class ResourceChecker(
    private val store: ResourceStore,
    private val behaviors: ResourceBehaviors,
    private val clock: Clock,
    private val timeout: Duration,
    private val telemetry: ResourceTelemetry,
) : AutoCloseable {
  private val log = LoggerFactory.getLogger(ResourceChecker::class.java)
  private val threads = AtomicInteger()
  private val executor = Executors.newCachedThreadPool { task ->
    Thread.ofPlatform()
        .name("resource-check-${threads.incrementAndGet()}")
        .daemon(true)
        .unstarted(task)
  }

  private val inFlight = ConcurrentHashMap<String, CompletableFuture<CheckResult>>()
  /** For a resource whose last check timed out: whether that check has ended at last. */
  private val blocked = ConcurrentHashMap<String, AtomicBoolean>()

  fun check(name: String, by: ApiIdentity): CheckOutcome {
    val resource = store.find(name) ?: return CheckOutcome.NotFound
    val mine = CompletableFuture<CheckResult>()
    val other = inFlight.putIfAbsent(name, mine)
    if (other != null) return CheckOutcome.Done(await(other))
    try {
      val result = checkAndKeep(resource)
      log.info(
          "Shared resource {} ({}) checked by {}: {}",
          name,
          resource.type.wireName,
          by.name,
          result.failure?.wire ?: "ok",
      )
      mine.complete(result)
      return CheckOutcome.Done(result)
    } catch (e: Throwable) {
      mine.completeExceptionally(e)
      throw e
    } finally {
      inFlight.remove(name, mine)
    }
  }

  private fun checkAndKeep(resource: SharedResource): CheckResult {
    val failure = probe(resource)
    val result = CheckResult(failure == null, failure, clock.instant())
    store.recordCheck(resource.name, result, resource.settings, resource.secretAlias)
    telemetry.checked(ResourceLabel(resource.name, resource.type.wireName), result.ok)
    return result
  }

  private fun probe(resource: SharedResource): CheckFailure? {
    val behavior = behaviors.of(resource.type) ?: return CheckFailure.ERROR
    if (blocked[resource.name]?.get() == false) return CheckFailure.TIMEOUT
    val ended = AtomicBoolean(false)
    val task =
        executor.submit<CheckFailure?> {
          try {
            behavior.check(resource)
          } finally {
            ended.set(true)
          }
        }
    return try {
      task.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
      blocked[resource.name] = ended
      task.cancel(true)
      CheckFailure.TIMEOUT
    } catch (e: ExecutionException) {
      val errorId = UUID.randomUUID()
      log.error(
          "Check of shared resource {} failed unexpectedly, errorId={}",
          resource.name,
          errorId,
          e.cause,
      )
      CheckFailure.ERROR
    }
  }

  private fun await(future: CompletableFuture<CheckResult>): CheckResult =
      try {
        future.get()
      } catch (e: ExecutionException) {
        throw e.cause ?: e
      }

  override fun close() {
    executor.shutdownNow()
  }
}
