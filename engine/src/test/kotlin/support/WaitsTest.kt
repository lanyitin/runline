package dev.lawlan.runline.engine.support

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.junit.jupiter.api.Timeout

/** The limits on waiting for another thread and for a condition, against real threads. */
@Timeout(30, unit = TimeUnit.SECONDS)
class WaitsTest {
  private val pool = Executors.newCachedThreadPool()
  private val never = CountDownLatch(1)

  @AfterTest
  fun stop() {
    never.countDown()
    pool.shutdownNow()
  }

  @Test
  fun `a task that does not finish fails at the limit and says what it was waited for`() {
    val stuck = pool.submit<Int> { never.await().let { 1 } }
    val begun = System.nanoTime()

    val failure =
        assertFailsWith<AssertionError> {
          stuck.getWithin("the first claim of the delivery", Duration.ofMillis(400))
        }

    assertTrue(Duration.ofNanos(System.nanoTime() - begun) < Duration.ofSeconds(10))
    assertTrue("the first claim of the delivery" in failure.message!!, failure.message)
    assertTrue("400 ms" in failure.message!!, failure.message)
  }

  @Test
  fun `a task that finishes gives its result`() {
    assertEquals(7, pool.submit<Int> { 7 }.getWithin("seven"))
  }

  @Test
  fun `a task that failed gives its own failure, not a timeout`() {
    val failed = pool.submit<Int> { error("the real reason") }

    val failure = assertFailsWith<IllegalStateException> { failed.getWithin("a task") }

    assertEquals("the real reason", failure.message)
  }

  @Test
  fun `a latch that is not released fails at the limit and says what it was waited for`() {
    val failure =
        assertFailsWith<AssertionError> {
          never.awaitWithin("the drain to begin", Duration.ofMillis(400))
        }

    assertTrue("the drain to begin" in failure.message!!, failure.message)
    assertTrue("400 ms" in failure.message!!, failure.message)
  }

  @Test
  fun `a latch that is released lets the wait end`() {
    val latch = CountDownLatch(1)
    pool.submit { latch.countDown() }

    latch.awaitWithin("the release")
  }

  @Test
  fun `a condition that never holds fails at the limit with what it was waited for and the state then`() {
    val failure =
        assertFailsWith<AssertionError> {
          awaitCondition(
              "the run to be RUNNING",
              Duration.ofMillis(400),
              diagnostics = { "the run is QUEUED" },
          ) {
            false
          }
        }

    assertTrue("the run to be RUNNING" in failure.message!!, failure.message)
    assertTrue("400 ms" in failure.message!!, failure.message)
    assertTrue("the run is QUEUED" in failure.message!!, failure.message)
  }

  @Test
  fun `a condition that comes true while waiting ends the wait`() {
    val ready = java.util.concurrent.atomic.AtomicBoolean(false)
    pool.submit {
      Thread.sleep(50)
      ready.set(true)
    }

    awaitCondition("the flag") { ready.get() }

    assertTrue(ready.get())
  }
}
