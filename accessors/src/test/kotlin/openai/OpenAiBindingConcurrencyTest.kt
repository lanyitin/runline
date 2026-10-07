package dev.lawlan.runline.accessors.openai

import dev.lawlan.runline.accessors.ResourceOperationFailure
import dev.lawlan.runline.accessors.fake.FakeOpenAiServer
import dev.lawlan.runline.core.ResourceFailure
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * How many requests a run has in flight at once, what stops a call from outside (the invalidation
 * of the run's accessors, the cancelling of the run), and what the host is told about each call.
 * Real threads, a real server, the real client.
 */
class OpenAiBindingConcurrencyTest {
  private val server = FakeOpenAiServer()
  private val pool = Executors.newCachedThreadPool()
  private val bindings = mutableListOf<OpenAiBinding>()

  @AfterTest
  fun stop() {
    pool.shutdownNow()
    bindings.forEach { it.close() }
    server.close()
  }

  private fun binding(
      extra: String = "",
      observer: OpenAiObserver = OpenAiObserver.NONE,
  ): OpenAiBinding {
    val text = """{"baseUrl":"${server.baseUrl}"${if (extra.isEmpty()) "" else ",$extra"}}"""
    val settings =
        (OpenAiSettings.parse(Json.parseToJsonElement(text).jsonObject) as SettingsResult.Valid)
            .settings
    return OpenAiBinding("lemon", settings, OpenAiCredential.None, observer).also { bindings += it }
  }

  private fun call(binding: OpenAiBinding, timeoutsMillis: Map<String, Long> = emptyMap()) =
      binding.execute(
          "openai.call",
          mapOf(
              "endpoint" to "chat.completions",
              "body" to "{}",
              "timeoutsMillis" to timeoutsMillis,
          ),
      )

  /** The failure a call comes to, or null when it succeeds; any other exception is a test bug. */
  private fun outcome(
      binding: OpenAiBinding,
      timeoutsMillis: Map<String, Long> = emptyMap(),
  ): ResourceFailure? =
      try {
        call(binding, timeoutsMillis)
        null
      } catch (e: ResourceOperationFailure) {
        e.failure
      }

  private fun await(what: String, seconds: Long = 10, condition: () -> Boolean) {
    val deadline = System.nanoTime() + seconds * 1_000_000_000
    while (!condition()) {
      check(System.nanoTime() < deadline) { "gave up waiting for $what" }
      Thread.sleep(10)
    }
  }

  private fun callsInParallel(binding: OpenAiBinding, count: Int): List<ResourceFailure?> {
    val start = CountDownLatch(1)
    val results: List<Future<ResourceFailure?>> =
        (1..count).map {
          pool.submit<ResourceFailure?> {
            start.await()
            outcome(binding)
          }
        }
    start.countDown()
    return results.map { it.get(60, TimeUnit.SECONDS) }
  }

  @Test
  fun `a run with one request at a time never has two at the service, however many threads ask`() {
    server.responseDelayMillis = 100

    val results = callsInParallel(binding(), 8)

    assertEquals(List(8) { null }, results)
    assertEquals(8, server.requests.size)
    assertEquals(1, server.peakInFlight)
  }

  @Test
  fun `a run with n requests at a time has n at the service and not more`() {
    server.responseDelayMillis = 200

    val results = callsInParallel(binding(""""requestsPerRun":3"""), 9)

    assertEquals(List(9) { null }, results)
    assertEquals(3, server.peakInFlight)
  }

  @Test
  fun `a call that gets no share in time is a quota wait timeout that cost the service nothing`() {
    server.responseDelayMillis = 1500
    val b = binding("""\"timeouts\":{\"quotaWaitMs\":60000}""".replace("\\", ""))
    val first = pool.submit<ResourceFailure?> { outcome(b) }
    await("the first request at the service") { server.inFlight == 1 }

    val start = System.nanoTime()
    val second = outcome(b, mapOf("quotaWait" to 200L))
    val waited = (System.nanoTime() - start) / 1_000_000

    assertEquals(ResourceFailure.QUOTA_WAIT_TIMEOUT, second)
    assertTrue(waited in 150..1200, "waited $waited ms")
    assertNull(first.get(10, TimeUnit.SECONDS))
    assertEquals(1, server.requests.size, "the refused call was never sent")
    server.responseDelayMillis = 0
    assertNull(outcome(b), "the share is free again")
  }

  @Test
  fun `the time spent waiting for a share is not counted against the first byte limit`() {
    server.responseDelayMillis = 600
    val b =
        binding("""\"timeouts\":{\"firstByteMs\":1000,\"quotaWaitMs\":10000}""".replace("\\", ""))

    // The second call waits about 600 ms for the first, then needs 600 ms itself: more than the
    // first byte limit if the wait counted, within it if it does not.
    val results = callsInParallel(b, 2)

    assertEquals(listOf(null, null), results)
  }

  @Test
  fun `abort cuts a request in flight, and the service sees the connection go`() {
    server.script = { _, response ->
      response.hang()
      true
    }
    val b = binding()
    val running = pool.submit<ResourceFailure?> { outcome(b) }
    await("the request at the service") { server.inFlight == 1 }

    val start = System.nanoTime()
    b.abort()
    val failure = running.get(10, TimeUnit.SECONDS)
    val took = (System.nanoTime() - start) / 1_000_000

    assertEquals(ResourceFailure.CANCELLED, failure)
    assertTrue(took < 2000, "took $took ms")
    await("the service to notice") { server.clientsGone == 1 }
    assertEquals(0, server.inFlight)
  }

  @Test
  fun `abort cuts a request whose answer has begun, and the service sees the connection go`() {
    server.script = { _, response ->
      response.begin(200, mapOf("Content-Type" to "application/json"), 1000)
      response.write("0123456789")
      response.hang()
      true
    }
    val b = binding()
    val running = pool.submit<ResourceFailure?> { outcome(b) }
    await("the answer to begin") { server.requests.size == 1 }
    Thread.sleep(200)

    b.abort()

    assertEquals(ResourceFailure.CANCELLED, running.get(10, TimeUnit.SECONDS))
    await("the service to notice") { server.clientsGone == 1 }
  }

  @Test
  fun `abort wakes the calls that wait for a share, and nothing is sent after it`() {
    server.script = { _, response ->
      response.hang()
      true
    }
    val b = binding("""\"timeouts\":{\"quotaWaitMs\":60000}""".replace("\\", ""))
    val holder = pool.submit<ResourceFailure?> { outcome(b) }
    await("the holder at the service") { server.inFlight == 1 }
    val waiters = (1..3).map { pool.submit<ResourceFailure?> { outcome(b) } }
    Thread.sleep(200)

    b.abort()

    assertEquals(ResourceFailure.CANCELLED, holder.get(10, TimeUnit.SECONDS))
    waiters.forEach { assertEquals(ResourceFailure.CANCELLED, it.get(10, TimeUnit.SECONDS)) }
    assertEquals(ResourceFailure.CANCELLED, outcome(b))
    assertEquals(1, server.requests.size, "only the one that was in flight was ever sent")
  }

  @Test
  fun `an interrupted thread stops its request and its wait, and stays interrupted`() {
    server.script = { _, response ->
      response.hang()
      true
    }
    val b = binding("""\"timeouts\":{\"quotaWaitMs\":60000}""".replace("\\", ""))
    val interruptedAfter = CopyOnWriteArrayList<Boolean>()
    val inFlight = Thread {
      val failure = outcome(b)
      interruptedAfter += Thread.currentThread().isInterrupted
      assertEquals(ResourceFailure.CANCELLED, failure)
    }
    inFlight.start()
    await("the request at the service") { server.inFlight == 1 }
    val waiter = Thread {
      val failure = outcome(b)
      interruptedAfter += Thread.currentThread().isInterrupted
      assertEquals(ResourceFailure.CANCELLED, failure)
    }
    waiter.start()
    Thread.sleep(200)

    waiter.interrupt()
    waiter.join(10_000)
    inFlight.interrupt()
    inFlight.join(10_000)

    assertFalse(waiter.isAlive)
    assertFalse(inFlight.isAlive)
    assertEquals(listOf(true, true), interruptedAfter)
    await("the service to notice") { server.clientsGone == 1 }
  }

  private class Recorder : OpenAiObserver {
    val started = CopyOnWriteArrayList<String>()
    val finished = CopyOnWriteArrayList<Pair<String, OpenAiOutcome>>()

    override fun started(resource: String, endpoint: String) {
      started += "$resource/$endpoint"
    }

    override fun finished(resource: String, endpoint: String, outcome: OpenAiOutcome) {
      finished += "$resource/$endpoint" to outcome
    }
  }

  @Test
  fun `the host is told of each call, with the time it waited and how long the service took`() {
    server.responseDelayMillis = 300
    val recorder = Recorder()
    val b = binding(observer = recorder)

    call(b)

    assertEquals(listOf("lemon/chat.completions"), recorder.started)
    val outcome = recorder.finished.single().second
    assertNull(outcome.failure)
    assertEquals(200, outcome.status)
    assertTrue(outcome.sent)
    assertTrue(outcome.quotaWaitMillis < 200, "waited ${outcome.quotaWaitMillis}")
    assertTrue(
        assertNotNull(outcome.firstByteMillis) >= 250,
        "first byte ${outcome.firstByteMillis}",
    )
    assertNotNull(outcome.generationMillis)
    assertEquals(OpenAiTokenUsage(3, 5, 8), outcome.usage)
  }

  @Test
  fun `a call that waited for a share says how long, and one that was refused or failed says what`() {
    server.responseDelayMillis = 500
    val recorder = Recorder()
    val b = binding("""\"timeouts\":{\"quotaWaitMs\":10000}""".replace("\\", ""), recorder)

    callsInParallel(b, 2)

    val waits = recorder.finished.map { it.second.quotaWaitMillis }.sorted()
    assertTrue(waits[0] < 200 && waits[1] >= 400, "waits $waits")

    recorder.finished.clear()
    server.responseDelayMillis = 0
    assertEquals(
        ResourceFailure.STREAM_NOT_SUPPORTED,
        try {
          b.execute(
              "openai.call",
              mapOf("endpoint" to "chat.completions", "body" to """{"stream":true}"""),
          )
          null
        } catch (e: ResourceOperationFailure) {
          e.failure
        },
    )
    server.script = { _, response ->
      response.json(429, "{}")
      true
    }
    assertEquals(ResourceFailure.RATE_LIMITED, outcome(b))

    val (refused, limited) = recorder.finished.map { it.second }
    assertEquals(ResourceFailure.STREAM_NOT_SUPPORTED, refused.failure)
    assertFalse(refused.sent)
    assertEquals(ResourceFailure.RATE_LIMITED, limited.failure)
    assertEquals(429, limited.status)
    assertTrue(limited.sent)
    assertNull(limited.usage)
    assertEquals(recorder.started.size, 3, "started once per call that was sent")
  }
}
