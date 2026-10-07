package dev.lawlan.runline.accessors.openai

import dev.lawlan.runline.accessors.ResourceOperationFailure
import dev.lawlan.runline.accessors.fake.FakeOpenAiServer
import dev.lawlan.runline.core.ResourceFailure
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The audio of a speech request pulled chunk by chunk (WI-53 with WI-47): raw bytes, not events,
 * under the same share of requests, limits and abort as a stream of events.
 */
class OpenAiBindingByteStreamTest {
  private val server = FakeOpenAiServer()
  private val bindings = mutableListOf<OpenAiBinding>()

  @AfterTest
  fun stop() {
    bindings.forEach { it.close() }
    server.close()
  }

  private fun binding(extra: String = ""): OpenAiBinding =
      OpenAiBinding(
              "lemon",
              (OpenAiSettings.parse(
                      Json.parseToJsonElement(
                              """{"baseUrl":"${server.baseUrl}","endpoints":["audio.speech","chat.completions"]${if (extra.isEmpty()) "" else ",$extra"}}"""
                          )
                          .jsonObject
                  ) as SettingsResult.Valid)
                  .settings,
          )
          .also { bindings += it }

  private fun open(
      binding: OpenAiBinding,
      endpoint: String = "audio.speech",
      body: String = """{"input":"hello","voice":"alloy","stream_format":"audio"}""",
      binary: Boolean = true,
  ): Map<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    return binding.execute(
        "openai.stream.open",
        mapOf("endpoint" to endpoint, "body" to body, "binary" to binary),
    ) as Map<String, Any?>
  }

  private fun next(binding: OpenAiBinding, opened: Map<String, Any?>): ByteArray? =
      binding.execute("openai.stream.next", mapOf("stream" to opened["stream"])) as ByteArray?

  @Test
  fun `the audio of a speech request is pulled as chunks of bytes that end with null, and the body is the pipeline's own`() {
    val b = binding()

    val opened = open(b)
    val all = ByteArrayOutputStream()
    var chunks = 0
    while (true) {
      val chunk = next(b, opened) ?: break
      all.write(chunk)
      chunks++
    }

    assertEquals(200, opened["status"])
    assertContentEquals(FakeOpenAiServer.SPEECH, all.toByteArray())
    assertTrue(chunks >= 1)
    assertNull(next(b, opened))
    val seen = server.requests.single()
    assertEquals("*/*", seen.header("accept"))
    assertEquals(
        Json.parseToJsonElement("""{"input":"hello","voice":"alloy","stream_format":"audio"}"""),
        Json.parseToJsonElement(seen.body),
        "no `stream` is added: the service streams its audio by itself",
    )
  }

  private fun await(what: String, seconds: Long = 15, condition: () -> Boolean) {
    val deadline = System.nanoTime() + seconds * 1_000_000_000
    while (!condition()) {
      check(System.nanoTime() < deadline) { "gave up waiting for $what" }
      Thread.sleep(10)
    }
  }

  /** A service that sends some audio and then goes on without ending the answer. */
  private fun endlessAudio() {
    server.script = { _, response ->
      response.beginChunked(200, mapOf("Content-Type" to "audio/mpeg"))
      response.chunk(ByteArray(100) { 3 })
      response.hang()
      true
    }
  }

  @Test
  fun `events are for the entries that send events and bytes for the entries that send bytes`() {
    val b = binding()

    val textOnSpeech = assertFailsWith<ResourceOperationFailure> { open(b, binary = false) }
    val bytesOnChat =
        assertFailsWith<ResourceOperationFailure> {
          open(b, endpoint = "chat.completions", body = "{}", binary = true)
        }

    assertEquals(ResourceFailure.STREAM_NOT_SUPPORTED, textOnSpeech.failure)
    assertEquals(ResourceFailure.STREAM_NOT_SUPPORTED, bytesOnChat.failure)
    assertEquals(0, server.requests.size)
  }

  @Test
  fun `a stream of bytes holds the share of requests until it ends or is closed`() {
    endlessAudio()
    val b = binding()
    val opened = open(b)
    next(b, opened)

    val second =
        assertFailsWith<ResourceOperationFailure> {
          b.execute(
              "openai.call",
              mapOf(
                  "endpoint" to "chat.completions",
                  "body" to "{}",
                  "timeoutsMillis" to mapOf("quotaWait" to 200L),
              ),
          )
        }
    b.execute("openai.stream.close", mapOf("stream" to opened["stream"]))

    assertEquals(ResourceFailure.QUOTA_WAIT_TIMEOUT, second.failure)
    await("the service to see the client leave") { server.clientsGone == 1 }
    assertNull(next(b, opened))
  }

  @Test
  fun `a gap in the audio longer than the idle limit is an idle timeout, and the bytes before it were delivered`() {
    endlessAudio()
    val b = binding("\"timeouts\":{\"idleMs\":300}")
    val opened = open(b)

    val first = next(b, opened)
    val e = assertFailsWith<ResourceOperationFailure> { next(b, opened) }

    assertEquals(100, first!!.size)
    assertEquals(ResourceFailure.IDLE_TIMEOUT, e.failure)
    await("the service to see the client leave") { server.clientsGone == 1 }
  }

  @Test
  fun `an abort cuts a pull of audio, which fails as cancelled, and the service sees the client leave`() {
    endlessAudio()
    val b = binding()
    val opened = open(b)
    next(b, opened)
    val pulling = Executors.newSingleThreadExecutor()
    try {
      val pull =
          pulling.submit<ResourceFailure?> {
            assertFailsWith<ResourceOperationFailure> { next(b, opened) }.failure
          }
      Thread.sleep(200)

      b.abort()

      assertEquals(ResourceFailure.CANCELLED, pull.get(5, TimeUnit.SECONDS))
      await("the service to see the client leave") { server.clientsGone == 1 }
    } finally {
      pulling.shutdownNow()
    }
  }
}
