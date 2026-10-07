package dev.lawlan.runline.accessors.openai

import dev.lawlan.runline.accessors.ResourceOperationFailure
import dev.lawlan.runline.accessors.fake.FakeOpenAiServer
import dev.lawlan.runline.core.ResourceFailure
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The requests of an `openai-compatible` resource against a real HTTP server (the Fake, on a real
 * socket) with the real JDK client: what goes out, what comes back, what is refused, and every kind
 * of failure as its own category with the status.
 */
class OpenAiBindingTest {
  private val key = "sk-test-0123456789abcdef"
  private val server = FakeOpenAiServer()
  private val others = mutableListOf<AutoCloseable>(server)
  private val bindings = mutableListOf<OpenAiBinding>()

  @AfterTest
  fun stop() {
    bindings.forEach { it.close() }
    others.forEach { it.close() }
  }

  private fun settings(extra: String = "", base: String = server.baseUrl): OpenAiSettings =
      (OpenAiSettings.parse(
              Json.parseToJsonElement(
                      """{"baseUrl":"$base"${if (extra.isEmpty()) "" else ",$extra"}}"""
                  )
                  .jsonObject
          ) as SettingsResult.Valid)
          .settings

  private fun binding(
      extra: String = "",
      credential: OpenAiCredential = OpenAiCredential.Key(key),
  ): OpenAiBinding = OpenAiBinding("lemon", settings(extra), credential).also { bindings += it }

  private fun call(
      binding: OpenAiBinding,
      endpoint: String = "chat.completions",
      body: String? = """{"messages":[{"role":"user","content":"hi"}]}""",
      extra: Map<String, Any?> = emptyMap(),
  ): Map<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    return binding.execute(
        "openai.call",
        mapOf("endpoint" to endpoint, "body" to body) + extra,
    ) as Map<String, Any?>
  }

  private fun failure(
      binding: OpenAiBinding,
      extra: Map<String, Any?> = emptyMap(),
      endpoint: String = "chat.completions",
      body: String? = "{}",
  ) = assertFailsWith<ResourceOperationFailure> { call(binding, endpoint, body, extra) }

  @Test
  fun `a chat completion goes to the service and the whole answer comes back`() {
    val answer = call(binding())

    assertEquals(200, answer["status"])
    assertTrue((answer["body"] as String).contains("chat.completion"))
    @Suppress("UNCHECKED_CAST") val headers = answer["headers"] as Map<String, List<String>>
    assertEquals(listOf("application/json"), headers["content-type"])
    val seen = server.requests.single()
    assertEquals("POST", seen.method)
    assertEquals("/v1/chat/completions", seen.path)
    assertEquals("application/json", seen.header("content-type"))
    assertEquals("application/json", seen.header("accept"))
    assertEquals("Bearer $key", seen.header("authorization"))
    assertTrue(seen.body.contains("\"messages\""))
  }

  @Test
  fun `the engine adds organization, project and the administrator's headers`() {
    val b = binding(""""organization":"org-1","project":"proj-9","headers":{"X-Team":"blue"}""")

    call(b)

    val seen = server.requests.single()
    assertEquals("org-1", seen.header("openai-organization"))
    assertEquals("proj-9", seen.header("openai-project"))
    assertEquals("blue", seen.header("x-team"))
  }

  @Test
  fun `nothing the pipeline gives can replace the key or add a header`() {
    val b = binding()

    // Whatever else is in the arguments is not looked at.
    call(
        b,
        extra =
            mapOf(
                "headers" to mapOf("Authorization" to "Bearer evil", "X-Evil" to "1"),
                "authorization" to "Bearer evil",
                "method" to "DELETE",
                "url" to "http://evil.example/",
            ),
    )

    val seen = server.requests.single()
    assertEquals("Bearer $key", seen.header("authorization"))
    assertNull(seen.header("x-evil"))
    assertEquals("POST", seen.method)
    assertEquals(1, seen.headers["authorization"]!!.size)
  }

  @Test
  fun `a resource without a key sends no authorization`() {
    call(binding(credential = OpenAiCredential.None))

    assertNull(server.requests.single().header("authorization"))
  }

  @Test
  fun `a key the keystore cannot give fails the call before anything is sent`() {
    val e = failure(binding(credential = OpenAiCredential.Unavailable))

    assertEquals(ResourceFailure.SECRET_UNAVAILABLE, e.failure)
    assertEquals(0, server.requests.size)
  }

  @Test
  fun `a request the rules refuse never reaches the service`() {
    val b = binding()

    assertEquals(
        ResourceFailure.ENDPOINT_NOT_ENABLED,
        failure(
                b,
                endpoint = "files.delete",
                body = null,
                extra = mapOf("pathParameters" to mapOf("id" to "f")),
            )
            .failure,
    )
    assertEquals(
        ResourceFailure.STREAM_NOT_SUPPORTED,
        failure(b, body = """{"stream":true}""").failure,
    )
    assertEquals(ResourceFailure.UNKNOWN_ENDPOINT, failure(b, endpoint = "../../etc").failure)
    assertEquals(0, server.requests.size)
  }

  @Test
  fun `the answer's headers carry no credential and no echo of the key`() {
    server.script = { _, response ->
      response.json(
          200,
          "{}",
          mapOf(
              "Set-Cookie" to "session=abc",
              "WWW-Authenticate" to "Bearer realm=x",
              "Proxy-Authenticate" to "Basic",
              "X-Api-Key" to "other",
              "Authentication-Info" to "nextnonce=1",
              "X-Request-Id" to "req-1",
              "X-Debug" to "you sent Bearer $key",
          ),
      )
      true
    }

    @Suppress("UNCHECKED_CAST")
    val headers = call(binding())["headers"] as Map<String, List<String>>

    assertEquals(
        setOf("content-type", "content-length", "connection", "x-request-id", "x-debug"),
        headers.keys.filter { it != "date" }.toSet(),
    )
    assertEquals(listOf("req-1"), headers["x-request-id"])
    assertEquals(listOf("you sent Bearer ***"), headers["x-debug"])
  }

  @Test
  fun `every kind of answer that is not a success is its own category with the status`() {
    val expected =
        mapOf(
            401 to ResourceFailure.DENIED,
            403 to ResourceFailure.DENIED,
            429 to ResourceFailure.RATE_LIMITED,
            500 to ResourceFailure.SERVER_ERROR,
            502 to ResourceFailure.SERVER_ERROR,
            503 to ResourceFailure.SERVER_ERROR,
            400 to ResourceFailure.REQUEST_REJECTED,
            404 to ResourceFailure.REQUEST_REJECTED,
            409 to ResourceFailure.REQUEST_REJECTED,
            422 to ResourceFailure.REQUEST_REJECTED,
        )
    val b = binding()

    for ((status, category) in expected) {
      server.script = { _, response ->
        response.json(status, """{"error":{"message":"echo $key"}}""", mapOf("X-Echo" to key))
        true
      }
      val e = failure(b)

      assertEquals(category, e.failure, "HTTP $status")
      assertEquals(status, e.status, "HTTP $status")
      val texts = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.toList()
      assertTrue(texts.none { it.contains(key) || it.contains("echo") }, "HTTP $status: $texts")
    }
  }

  @Test
  fun `a service that cannot be reached is a connection failure`() {
    val closed = ServerSocket(0).use { it.localPort }
    val b =
        OpenAiBinding(
            "lemon",
            settings(base = "http://127.0.0.1:$closed/v1"),
            OpenAiCredential.Key(key),
        )
    bindings += b

    val e = failure(b)

    assertEquals(ResourceFailure.CONNECTION_FAILED, e.failure)
    assertNull(e.status)
  }

  @Test
  fun `a redirect inside the base address is followed with the same request`() {
    server.script = { request, response ->
      if (request.path == "/v1/chat/completions") {
        response.redirect(307, "/v1/inner/chat")
      } else {
        response.json(200, """{"at":"${request.path}","echo":${request.body.length}}""")
      }
      true
    }

    val answer = call(binding(), body = """{"messages":[]}""")

    assertEquals(200, answer["status"])
    assertTrue((answer["body"] as String).contains("/v1/inner/chat"))
    val second = server.requests[1]
    assertEquals("POST", second.method)
    assertEquals("Bearer $key", second.header("authorization"))
    assertEquals("""{"messages":[]}""", second.body)
  }

  @Test
  fun `a redirect that leaves the base address is not followed and the other place never hears of it`() {
    val elsewhere = FakeOpenAiServer().also { others += it }
    val targets =
        listOf(
            "${elsewhere.baseUrl}/chat/completions",
            "http://localhost:${server.port}/v1/chat",
            "${server.origin}/other/chat",
            "${server.origin}/v10/chat",
            "/v1/../admin",
            "/admin",
            "https://127.0.0.1:${server.port}/v1/chat",
            "//evil.example/v1/chat",
            "../../outside",
        )
    val b = binding()

    for (target in targets) {
      server.script = { _, response ->
        response.redirect(302, target)
        true
      }
      val e = failure(b)

      assertEquals(ResourceFailure.REDIRECT_BLOCKED, e.failure, target)
      assertEquals(302, e.status, target)
    }
    assertEquals(0, elsewhere.requests.size)
    assertTrue(
        server.requests.all { it.path == "/v1/chat/completions" },
        "no second request was made",
    )
  }

  @Test
  fun `a redirect without a place, or in a circle, is blocked`() {
    val b = binding()
    server.script = { _, response ->
      response.complete(302, ByteArray(0))
      true
    }
    assertEquals(ResourceFailure.REDIRECT_BLOCKED, failure(b).failure)

    server.script = { _, response ->
      response.redirect(307, "/v1/chat/completions")
      true
    }
    assertEquals(ResourceFailure.REDIRECT_BLOCKED, failure(b).failure)
    assertFalse(server.requests.size > 12, "the circle was cut short")
  }
}
