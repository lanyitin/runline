package dev.lawlan.runline.engine

import dev.lawlan.runline.engine.support.awaitWithin
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class InFlightRequestsTest {
  private val requests = InFlightRequests()
  private val background = Executors.newCachedThreadPool()

  @AfterTest
  fun stop() {
    background.shutdownNow()
  }

  private fun awaitUntil(condition: () -> Boolean) {
    val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
    while (!condition()) {
      check(System.nanoTime() < deadline) { "condition not met" }
      Thread.sleep(5)
    }
  }

  @Test
  fun `draining with nothing in flight returns at once`() {
    assertTrue(requests.drain(Duration.ofSeconds(5)))
  }

  @Test
  fun `draining waits for every request in flight to end`() {
    assertTrue(requests.tryBegin())
    assertTrue(requests.tryBegin())
    val drained = background.submit<Boolean> { requests.drain(Duration.ofSeconds(10)) }

    Thread.sleep(200)
    assertFalse(drained.isDone, "one more request than ended: still draining")
    requests.end()
    Thread.sleep(200)
    assertFalse(drained.isDone, "one request is still in flight")
    requests.end()

    assertTrue(drained.get(5, TimeUnit.SECONDS))
  }

  @Test
  fun `once draining has begun no new request is admitted`() {
    assertTrue(requests.tryBegin())
    val started = CountDownLatch(1)
    background.submit {
      started.countDown()
      requests.drain(Duration.ofSeconds(10))
    }
    started.awaitWithin("the drain to be started on its own thread")
    // The drain runs on another thread: wait until it has begun, which an uncounted probe shows.
    awaitUntil { !requests.tryBegin(counted = false) }

    assertFalse(requests.tryBegin(), "a stopping Engine admits nothing new")
    requests.end()
  }

  @Test
  fun `draining gives up at the time limit and says so`() {
    assertTrue(requests.tryBegin())

    val started = System.nanoTime()
    val drained = requests.drain(Duration.ofMillis(300))
    val millis = (System.nanoTime() - started) / 1_000_000

    assertFalse(drained)
    assertTrue(millis in 250..2_000, "waited $millis ms")
  }

  @Test
  fun `a session that is not counted does not hold up draining but is not admitted once it began`() {
    assertTrue(requests.tryBegin(counted = false))

    assertTrue(requests.drain(Duration.ofSeconds(5)), "an uncounted session is not waited for")
    assertFalse(requests.tryBegin(counted = false))
  }
}
