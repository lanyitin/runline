package dev.lawlan.runline.accessors.fake

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Where a server under the contract is and how to talk to it. */
class ContractTarget(
    /** The base address, with the root path (`http://host:port/v1`). */
    val baseUrl: String,
    val model: String,
    /** The key the server requires, if it requires one. */
    val apiKey: String? = null,
)

/**
 * What an OpenAI compatible server answers, as far as the Engine relies on it (WI-46): the same
 * tests run on the Fake that stands in for such a server in the Engine's tests and, where one is
 * available, on a real service, so the Fake cannot drift from the protocol. The client here is the
 * JDK's own, not the Engine's code, so that the contract judges the server only.
 */
abstract class OpenAiServerContract {
  protected abstract fun target(): ContractTarget

  private val http: HttpClient =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  private fun send(
      method: String,
      path: String,
      body: String? = null,
      key: String? = target().apiKey,
  ): HttpResponse<String> {
    val builder =
        HttpRequest.newBuilder(URI.create(target().baseUrl + path)).timeout(Duration.ofMinutes(5))
    if (key != null) builder.header("Authorization", "Bearer $key")
    if (body != null) builder.header("Content-Type", "application/json")
    builder.method(
        method,
        if (body == null) HttpRequest.BodyPublishers.noBody()
        else HttpRequest.BodyPublishers.ofString(body),
    )
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
  }

  private fun json(response: HttpResponse<String>): JsonObject =
      Json.parseToJsonElement(response.body()).jsonObject

  private fun assertErrorShape(response: HttpResponse<String>) {
    val error = json(response)["error"]
    assertTrue(error is JsonObject, "an error answer has an `error` object: ${response.body()}")
    assertTrue(error["message"] is JsonPrimitive, "the error has a message: ${response.body()}")
  }

  @Test
  fun `a chat completion answers a chat completion object with the message and the usage`() {
    val response =
        send(
            "POST",
            "/chat/completions",
            """{"model":"${target().model}","messages":[{"role":"user","content":"Say hi"}],"max_tokens":8}""",
        )

    assertEquals(200, response.statusCode(), response.body())
    assertTrue(
        response.headers().firstValue("content-type").orElse("").startsWith("application/json")
    )
    val body = json(response)
    assertEquals("chat.completion", body["object"]!!.jsonPrimitive.content)
    val choice = body["choices"]!!.jsonArray[0].jsonObject
    assertEquals("assistant", choice["message"]!!.jsonObject["role"]!!.jsonPrimitive.content)
    assertTrue(choice["message"]!!.jsonObject["content"]!!.jsonPrimitive.isString)
    val usage = body["usage"]!!.jsonObject
    assertTrue(usage["total_tokens"]!!.jsonPrimitive.content.toLong() >= 0)
    assertTrue(usage["prompt_tokens"]!!.jsonPrimitive.content.toLong() >= 0)
    assertTrue(usage["completion_tokens"]!!.jsonPrimitive.content.toLong() >= 0)
  }

  /** The lines of the answer to a streamed request, read as they come. */
  private fun streamed(path: String, body: String): Pair<HttpResponse<*>, List<String>> {
    val builder =
        HttpRequest.newBuilder(URI.create(target().baseUrl + path))
            .timeout(Duration.ofMinutes(5))
            .header("Content-Type", "application/json")
    target().apiKey?.let { builder.header("Authorization", "Bearer $it") }
    val response =
        http.send(
            builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofLines(),
        )
    return response to response.body().toList()
  }

  @Test
  fun `a streamed chat completion is a stream of data events of chunk objects that ends with DONE`() {
    val (response, lines) =
        streamed(
            "/chat/completions",
            """{"model":"${target().model}","messages":[{"role":"user","content":"Say hi"}],"max_tokens":8,"stream":true}""",
        )

    assertEquals(200, response.statusCode(), lines.joinToString("\n"))
    assertTrue(
        response.headers().firstValue("content-type").orElse("").startsWith("text/event-stream")
    )
    val events = lines.filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }
    assertTrue(events.size >= 2, "chunks and the end: $lines")
    assertEquals("[DONE]", events.last())
    for (event in events.dropLast(1)) {
      assertEquals("chat.completion.chunk", json(event)["object"]!!.jsonPrimitive.content)
    }
    assertTrue(
        events.dropLast(1).any {
          json(it)["choices"]!!.jsonArray.firstOrNull()?.jsonObject?.get("delta") != null
        },
        "some chunk carries a delta: $events",
    )
  }

  private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

  @Test
  fun `a streamed chat completion reports its usage in a last chunk when it is asked to`() {
    val (_, lines) =
        streamed(
            "/chat/completions",
            """{"model":"${target().model}","messages":[{"role":"user","content":"Say hi"}],"max_tokens":8,"stream":true,"stream_options":{"include_usage":true}}""",
        )

    val events =
        lines
            .filter { it.startsWith("data:") }
            .map { it.removePrefix("data:").trim() }
            .filter { it != "[DONE]" }
            .map(::json)
    val usage = events.last()["usage"]?.jsonObject
    assertTrue(usage != null, "the last chunk carries the usage: $lines")
    assertTrue(usage["total_tokens"]!!.jsonPrimitive.content.toLong() >= 0)
    assertTrue(
        events.dropLast(1).all {
          it["usage"] == null || it["usage"] is kotlinx.serialization.json.JsonNull
        }
    )
  }

  @Test
  fun `the list of models is a list of model objects, each one retrievable`() {
    val list = send("GET", "/models")

    assertEquals(200, list.statusCode(), list.body())
    val body = json(list)
    assertEquals("list", body["object"]!!.jsonPrimitive.content)
    val models: JsonArray = body["data"]!!.jsonArray
    assertTrue(models.isNotEmpty())
    val first = models[0].jsonObject
    assertEquals("model", first["object"]!!.jsonPrimitive.content)
    val id = first["id"]!!.jsonPrimitive.content

    val one = send("GET", "/models/$id")
    assertEquals(200, one.statusCode(), one.body())
    assertEquals(id, json(one)["id"]!!.jsonPrimitive.content)
  }

  @Test
  fun `an unknown model is a 404 with an error object`() {
    val response = send("GET", "/models/there-is-no-such-model-1234")

    assertEquals(404, response.statusCode(), response.body())
    assertErrorShape(response)
  }

  @Test
  fun `embeddings answer a list of vectors`() {
    val response = send("POST", "/embeddings", """{"model":"${target().model}","input":"hello"}""")

    assertEquals(200, response.statusCode(), response.body())
    val body = json(response)
    assertEquals("list", body["object"]!!.jsonPrimitive.content)
    val item = body["data"]!!.jsonArray[0].jsonObject
    assertEquals("embedding", item["object"]!!.jsonPrimitive.content)
    assertTrue(item["embedding"]!!.jsonArray.isNotEmpty())
  }

  @Test
  fun `a completion answers a text completion object`() {
    val response =
        send(
            "POST",
            "/completions",
            """{"model":"${target().model}","prompt":"Once","max_tokens":4}""",
        )

    assertEquals(200, response.statusCode(), response.body())
    val body = json(response)
    assertEquals("text_completion", body["object"]!!.jsonPrimitive.content)
    assertTrue(body["choices"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.isString)
  }

  @Test
  fun `a path the server does not have is a 404 with an error object`() {
    val response = send("GET", "/there/is/nothing/here")

    assertEquals(404, response.statusCode(), response.body())
    assertErrorShape(response)
  }

  @Test
  fun `a body that is not JSON is a client error with an error object`() {
    val response = send("POST", "/chat/completions", "this is not json")

    assertTrue(response.statusCode() in 400..499, "${response.statusCode()} ${response.body()}")
    assertErrorShape(response)
  }

  @Test
  fun `a wrong key is a 401 with an error object, where the server requires a key`() {
    if (target().apiKey == null) return

    val response = send("GET", "/models", key = "not-the-key")

    assertEquals(401, response.statusCode(), response.body())
    assertErrorShape(response)
  }
}
