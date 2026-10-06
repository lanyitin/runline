package dev.lawlan.runline.engine.support

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/*
 * Waits with a limit. A wait that outlasts its limit fails with what was waited for and for how
 * long, and a wait that ends because the other side failed shows that failure.
 */

private fun Duration.text() = "${toMillis()} ms"

/**
 * The result of this task; fails if it has not finished in [timeout]. A task's own failure is
 * rethrown.
 */
fun <T> Future<T>.getWithin(what: String, timeout: Duration = TestTimeouts.task): T =
    try {
      get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
      cancel(true)
      throw AssertionError("not finished within ${timeout.text()}: $what")
    } catch (e: ExecutionException) {
      throw e.cause ?: e
    }

/** Waits for this latch to open; fails if it has not within [timeout]. */
fun CountDownLatch.awaitWithin(what: String, timeout: Duration = TestTimeouts.handshake) {
  if (!await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
    throw AssertionError("not reached within ${timeout.text()}: $what")
  }
}

/**
 * Waits until [condition] holds; fails after [timeout] with what was waited for and [diagnostics],
 * the state of the system at that moment.
 */
fun awaitCondition(
    what: String,
    timeout: Duration = TestTimeouts.condition,
    diagnostics: () -> String = { "" },
    condition: () -> Boolean,
) {
  val deadline = System.nanoTime() + timeout.toNanos()
  while (!condition()) {
    if (System.nanoTime() >= deadline) {
      val state = diagnostics()
      throw AssertionError(
          "not true within ${timeout.text()}: $what" + if (state.isEmpty()) "" else "\n$state"
      )
    }
    Thread.sleep(10)
  }
}
