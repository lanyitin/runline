package dev.lawlan.runline.accessors.openai

import dev.lawlan.runline.accessors.ResourceOperationFailure
import dev.lawlan.runline.accessors.fake.FakeOpenAiServer
import dev.lawlan.runline.core.ResourceFailure
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
}
