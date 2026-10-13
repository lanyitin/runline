package dev.lawlan.runline.accessors.openai

import dev.lawlan.runline.accessors.ResourceOperationFailure
import dev.lawlan.runline.accessors.fake.FakeOpenAiServer
import dev.lawlan.runline.core.ResourceFailure
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * What a 4xx of an `openai-compatible` service tells the pipeline (ADR-043, WI-1430): only
 * `error.type` and `error.message`, bounded, printable, masked; against a real HTTP server.
 */
class OpenAiBindingUpstreamErrorTest {
  private val key = "sk-test-0123456789abcdef"
  private val server = FakeOpenAiServer()
  private val bindings = mutableListOf<OpenAiBinding>()

  @AfterTest
  fun stop() {
    bindings.forEach { it.close() }
    server.close()
  }

  private fun binding(mask: (String) -> String = { it }): OpenAiBinding =
      OpenAiBinding(
              "lemon",
              (OpenAiSettings.parse(
                      Json.parseToJsonElement("""{"baseUrl":"${server.baseUrl}"}""").jsonObject
                  ) as SettingsResult.Valid)
                  .settings,
              OpenAiCredential.Key(key),
              mask = mask,
          )
          .also { bindings += it }

  private fun answer(status: Int, body: String, type: String = "application/json") {
    server.script = { _, response ->
      response.complete(status, body.encodeToByteArray(), mapOf("Content-Type" to type))
      true
    }
  }

  private fun failure(b: OpenAiBinding = binding()): ResourceOperationFailure =
      assertFailsWith<ResourceOperationFailure> {
        b.execute("openai.call", mapOf("endpoint" to "chat.completions", "body" to "{}"))
      }

  @Test
  fun `a 4xx in the standard error format gives its type and message`() {
    answer(
        400,
        """{"error":{"message":"model 'x' does not support this endpoint","type":"invalid_request_error","code":"c"}}""",
    )

    val e = failure()

    assertEquals(ResourceFailure.REQUEST_REJECTED, e.failure)
    assertEquals(400, e.status)
    assertEquals("invalid_request_error", e.upstreamType)
    assertEquals("model 'x' does not support this endpoint", e.upstreamMessage)
  }

  @Test
  fun `a 4xx whose body is not JSON gives nothing and none of the body`() {
    answer(400, "<html>proxy says: no such model, token $key</html>", "text/html")

    val e = failure()

    assertEquals(ResourceFailure.REQUEST_REJECTED, e.failure)
    assertEquals("", e.upstreamType)
    assertEquals("", e.upstreamMessage)
  }

  @Test
  fun `a 4xx in JSON without the two fields gives nothing of its other fields`() {
    answer(404, """{"detail":"secret internals","error":{"code":"x","param":"p"}}""")

    val e = failure()

    assertEquals("", e.upstreamType)
    assertEquals("", e.upstreamMessage)
  }

  @Test
  fun `a long message and a long type are cut with a mark within their limits`() {
    answer(
        422,
        """{"error":{"type":"${"t".repeat(500)}","message":"${"m".repeat(3000)}"}}""",
    )

    val e = failure()

    assertEquals(64, e.upstreamType.length)
    assertTrue(e.upstreamType.endsWith("\u2026"))
    assertEquals(300, e.upstreamMessage.length)
    assertTrue(e.upstreamMessage.endsWith("\u2026"))
    assertEquals("m".repeat(299), e.upstreamMessage.dropLast(1))
  }

  @Test
  fun `a message that holds a secret the host knows has it masked`() {
    answer(400, """{"error":{"message":"bad value hunter2-token in input","type":"t"}}""")

    val e = failure(binding(mask = { it.replace("hunter2-token", "***") }))

    assertEquals("bad value *** in input", e.upstreamMessage)
  }

  @Test
  fun `a message that holds the key of the resource has it masked without the host`() {
    answer(401, """{"error":{"message":"Incorrect API key provided: $key.","type":"auth"}}""")

    val e = failure()

    assertFalse(e.upstreamMessage.contains(key))
    assertEquals("Incorrect API key provided: ***.", e.upstreamMessage)
  }

  @Test
  fun `control characters and line breaks are not kept`() {
    answer(
        400,
        """{"error":{"type":"a\u0000b","message":"line one\nline two\r\n\u001b[31mred\u0007\tend"}}""",
    )

    val e = failure()

    assertEquals("a b", e.upstreamType)
    assertEquals("line one line two [31mred end", e.upstreamMessage)
  }

  @Test
  fun `a long body is read only at its front`() {
    answer(
        400,
        """{"error":{"type":"t","message":"m"},"padding":"${"x".repeat(200_000)}"}""",
    )

    val e = failure()

    // The front is cut before the end of the JSON, so it cannot be parsed: nothing is given.
    assertEquals("", e.upstreamMessage)
  }

  @Test
  fun `a 5xx gives no detail whatever its body says`() {
    answer(503, """{"error":{"type":"overloaded","message":"come back later"}}""")

    val e = failure()

    assertEquals(ResourceFailure.SERVER_ERROR, e.failure)
    assertEquals(503, e.status)
    assertEquals("", e.upstreamType)
    assertEquals("", e.upstreamMessage)
  }

  @Test
  fun `the detail is not part of the message of the failure`() {
    answer(400, """{"error":{"type":"t","message":"visible only as a field"}}""")

    val e = failure()

    assertEquals("REQUEST_REJECTED", e.message)
  }
}
