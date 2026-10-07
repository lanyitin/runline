package dev.lawlan.runline.accessors.openai

import dev.lawlan.runline.accessors.ResourceOperationFailure
import dev.lawlan.runline.accessors.fake.ClientGone
import dev.lawlan.runline.accessors.fake.FakeOpenAiServer
import dev.lawlan.runline.core.ResourceFailure
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * A streamed answer pulled piece by piece (WI-47) against a real HTTP server (the Fake, on a real
 * socket, chunked server-sent events) with the real JDK client: what a pipeline gets for each pull,
 * what ends the stream, and what it costs. Only JDK types cross the binding.
 */
class OpenAiBindingStreamTest {
  private val server = FakeOpenAiServer()
  private val bindings = mutableListOf<OpenAiBinding>()

  @AfterTest
  fun stop() {
    bindings.forEach { it.close() }
    server.close()
  }

  private fun binding(extra: String = ""): OpenAiBinding {
    val text = """{"baseUrl":"${server.baseUrl}"${if (extra.isEmpty()) "" else ",$extra"}}"""
    val settings =
        (OpenAiSettings.parse(Json.parseToJsonElement(text).jsonObject) as SettingsResult.Valid)
            .settings
    return OpenAiBinding("lemon", settings).also { bindings += it }
  }

  private fun open(
      binding: OpenAiBinding,
      endpoint: String = "chat.completions",
      body: String? = """{"messages":[{"role":"user","content":"hi"}]}""",
  ): Map<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    return binding.execute("openai.stream.open", mapOf("endpoint" to endpoint, "body" to body))
        as Map<String, Any?>
  }

  private fun next(binding: OpenAiBinding, opened: Map<String, Any?>): String? =
      binding.execute("openai.stream.next", mapOf("stream" to opened["stream"])) as String?

  private fun close(binding: OpenAiBinding, opened: Map<String, Any?>) {
    binding.execute("openai.stream.close", mapOf("stream" to opened["stream"]))
  }

  private fun failure(block: () -> Any?) =
      kotlin.test.assertFailsWith<ResourceOperationFailure>(block = block)

  @Test
  fun `a streamed answer is pulled one event at a time and ends with null`() {
    val b = binding()

    val opened = open(b)
    val chunks = generateSequence { next(b, opened) }.toList()

    assertEquals(200, opened["status"])
    @Suppress("UNCHECKED_CAST") val headers = opened["headers"] as Map<String, List<String>>
    assertEquals(listOf("text/event-stream"), headers["content-type"])
    assertEquals(6, chunks.size, chunks.toString())
    assertTrue(chunks.first().contains("\"role\":\"assistant\""), chunks.first())
    assertEquals(
        "Hello from the fake",
        chunks.drop(1).take(4).joinToString("") {
          Json.parseToJsonElement(it)
              .jsonObject["choices"]!!
              .toString()
              .substringAfter("\"content\":\"")
              .substringBefore('"')
        },
    )
    assertNull(next(b, opened), "after the end there is nothing more")
    assertEquals(
        """true""",
        Json.parseToJsonElement(server.requests.single().body).jsonObject["stream"].toString(),
    )
  }

  @Test
  fun `an entry that cannot stream is refused, and so is a body that sets stream itself, before anything is sent`() {
    val b = binding("\"endpoints\":[\"chat.completions\",\"embeddings\"]")

    val entry = failure { open(b, "embeddings", """{"model":"m","input":"x"}""") }
    val own = failure { open(b, body = """{"messages":[],"stream":true}""") }
    val off = failure { open(b, body = """{"messages":[],"stream":false}""") }
    assertEquals(ResourceFailure.STREAM_NOT_SUPPORTED, entry.failure)
    assertEquals(ResourceFailure.INVALID_ARGUMENT, own.failure)
    assertEquals(ResourceFailure.INVALID_ARGUMENT, off.failure)
    assertEquals(0, server.requests.size)
  }

  private fun callWithQuotaWait(binding: OpenAiBinding, millis: Long): Map<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    return binding.execute(
        "openai.call",
        mapOf(
            "endpoint" to "chat.completions",
            "body" to "{}",
            "timeoutsMillis" to mapOf("quotaWait" to millis),
        ),
    ) as Map<String, Any?>
  }

  @Test
  fun `a stream holds the run's share of requests for as long as it lasts and gives it back at its end`() {
    server.chunkDelayMillis = 100
    val b = binding()
    val opened = open(b)
    next(b, opened)

    val waiting = failure { callWithQuotaWait(b, 200) }
    assertEquals(ResourceFailure.QUOTA_WAIT_TIMEOUT, waiting.failure)
    assertEquals(1, server.inFlight, "the stream is the one request the service has")

    while (next(b, opened) != null) {
      // read to the end
    }
    assertEquals(200, callWithQuotaWait(b, 200)["status"])
    assertEquals(1, server.peakInFlight)
  }

  private fun await(what: String, seconds: Long = 10, condition: () -> Boolean) {
    val deadline = System.nanoTime() + seconds * 1_000_000_000
    while (!condition()) {
      check(System.nanoTime() < deadline) { "gave up waiting for $what" }
      Thread.sleep(10)
    }
  }

  /** A service that sends one event and then goes on without ending the stream. */
  private fun endless() {
    server.script = { _, response ->
      response.beginChunked(200, mapOf("Content-Type" to "text/event-stream"))
      response.event("""{"n":1}""")
      response.hang()
      true
    }
  }

  @Test
  fun `a pipeline that closes a stream drops the connection at once and has its share back`() {
    endless()
    val b = binding()
    val opened = open(b)
    assertEquals("""{"n":1}""", next(b, opened))

    close(b, opened)

    await("the service to see the connection go") { server.clientsGone == 1 }
    assertEquals(0, server.inFlight)
    server.script = null
    assertEquals(200, callWithQuotaWait(b, 200)["status"])
    assertNull(next(b, opened), "a closed stream has nothing more")
    close(b, opened)
  }

  @Test
  fun `an abort cuts a stream that is being pulled, the service sees the connection go, and the pull fails as cancelled`() {
    endless()
    val b = binding()
    val opened = open(b)
    next(b, opened)
    val pulling = Executors.newSingleThreadExecutor()
    try {
      val pull = pulling.submit<ResourceFailure?> { failure { next(b, opened) }.failure }
      Thread.sleep(200)

      b.abort()

      assertEquals(ResourceFailure.CANCELLED, pull.get(5, TimeUnit.SECONDS))
      await("the service to see the connection go") { server.clientsGone == 1 }
      assertEquals(0, server.inFlight)
      assertEquals(ResourceFailure.CANCELLED, failure { next(b, opened) }.failure)
    } finally {
      pulling.shutdownNow()
    }
  }

  @Test
  fun `a run that is cancelled while it pulls ends the stream as cancelled, and stays interrupted`() {
    endless()
    val b = binding()
    val opened = open(b)
    next(b, opened)
    val pulling = Executors.newSingleThreadExecutor()
    try {
      val seen = AtomicReference<Pair<ResourceFailure, Boolean>>()
      val pull = pulling.submit {
        val e = failure { next(b, opened) }
        seen.set(e.failure to Thread.currentThread().isInterrupted)
      }
      Thread.sleep(200)

      pulling.shutdownNow()

      pull.get(5, TimeUnit.SECONDS)
      assertEquals(ResourceFailure.CANCELLED to true, seen.get())
      await("the service to see the connection go") { server.clientsGone == 1 }
    } finally {
      pulling.shutdownNow()
    }
  }

  @Test
  fun `a service that breaks off in the middle of a stream is a connection failure, and what came before was delivered`() {
    server.script = { _, response ->
      response.beginChunked(200, mapOf("Content-Type" to "text/event-stream"))
      response.event("""{"n":1}""")
      response.event("""{"n":2}""")
      Thread.sleep(100)
      response.abort()
      throw ClientGone()
    }
    val b = binding()
    val opened = open(b)

    val before = listOf(next(b, opened), next(b, opened))
    val e = failure { next(b, opened) }

    assertEquals(listOf("""{"n":1}""", """{"n":2}"""), before)
    assertEquals(ResourceFailure.CONNECTION_FAILED, e.failure)
    assertEquals(ResourceFailure.CONNECTION_FAILED, failure { next(b, opened) }.failure)
    assertEquals(0, server.inFlight)
    server.script = null
    assertEquals(200, callWithQuotaWait(b, 200)["status"], "the share is free again")
  }

  /** A service that answers at once and then takes [firstMillis] to send the first event. */
  private fun prefill(firstMillis: Long) {
    server.script = { _, response ->
      response.beginChunked(200, mapOf("Content-Type" to "text/event-stream"))
      response.pause(firstMillis)
      response.event("""{"n":1}""")
      response.endChunked()
      true
    }
  }

  @Test
  fun `the first byte limit covers the time to the first event, not only to the headers`() {
    prefill(2000)
    val b = binding("\"timeouts\":{\"firstByteMs\":300}")

    val start = System.nanoTime()
    val opened = open(b)
    val opening = (System.nanoTime() - start) / 1_000_000
    val e = failure { next(b, opened) }
    val took = (System.nanoTime() - start) / 1_000_000

    assertTrue(opening < 250, "the headers are there at once: $opening ms")
    assertEquals(ResourceFailure.FIRST_BYTE_TIMEOUT, e.failure)
    assertTrue(took in 250..1500, "took $took ms")
    await("the service to see the connection go") { server.clientsGone == 1 }
  }

  @Test
  fun `a first event within the first byte limit is delivered, however long the idle limit is not`() {
    prefill(600)
    val b = binding("\"timeouts\":{\"firstByteMs\":5000,\"idleMs\":200}")

    val opened = open(b)

    assertEquals("""{"n":1}""", next(b, opened))
    assertNull(next(b, opened))
  }

  @Test
  fun `a gap between two events longer than the idle limit is an idle timeout, and the events before it were delivered`() {
    server.script = { _, response ->
      response.beginChunked(200, mapOf("Content-Type" to "text/event-stream"))
      response.event("""{"n":1}""")
      response.pause(3000)
      response.event("""{"n":2}""")
      response.endChunked()
      true
    }
    val b = binding("\"timeouts\":{\"idleMs\":300}")
    val opened = open(b)

    val first = next(b, opened)
    val start = System.nanoTime()
    val e = failure { next(b, opened) }
    val took = (System.nanoTime() - start) / 1_000_000

    assertEquals("""{"n":1}""", first)
    assertEquals(ResourceFailure.IDLE_TIMEOUT, e.failure)
    assertTrue(took in 250..1500, "took $took ms")
    await("the service to see the connection go") { server.clientsGone == 1 }
    assertEquals(0, server.inFlight)
  }
}
