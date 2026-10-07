package dev.lawlan.runline.core

import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.createDirectories
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

/**
 * The accessor of an `openai-compatible` resource as a pipeline sees it (ADR-019, WI-46): the same
 * rules as every accessor about who may have one, and a call that turns into one operation of the
 * host with JDK types only. The host here is a function: it is the boundary of the run, not an
 * external system.
 */
class OpenAiAccessorTest {
  @TempDir lateinit var tmp: Path

  private val seen = mutableListOf<Map<String, Any?>>()
  private var answer: Map<String, Any?> = ok(200, emptyMap(), "{}")

  private fun ok(status: Int, headers: Map<String, List<String>>, body: String) =
      mapOf(
          "ok" to true,
          "value" to mapOf("status" to status, "headers" to headers, "body" to body),
      )

  private fun context(
      declared: Map<String, String> = mapOf("lemon" to "openai-compatible"),
      provided: Map<String, String> = declared,
  ): PipelineContext {
    val metadata =
        PipelineMetadata(
            "demo",
            emptyList(),
            emptyMap(),
            AccessPolicy.Allow(emptySet()),
            AccessPolicy.Allow(emptySet()),
            declared.keys,
            declared,
        )
    return RestrictedContext(
        metadata,
        emptyMap(),
        tmp.resolve("shared").createDirectories(),
        tmp.resolve("run").createDirectories(),
        resources =
            ResourceLink(provided) {
              seen += it
              answer
            },
    )
  }

  @Test
  fun `a declared and provided openai-compatible resource gives an accessor`() {
    assertNotNull(context().accessors.openAiCompatible("lemon"))
  }

  @Test
  fun `a resource that was not declared, or declared by name only, or of another type is refused`() {
    val none =
        assertFailsWith<ResourceAccessException> { context().accessors.openAiCompatible("x") }
    val nameOnly =
        assertFailsWith<ResourceAccessException> {
          RestrictedContext(
                  PipelineMetadata(
                      "demo",
                      emptyList(),
                      emptyMap(),
                      AccessPolicy.Allow(emptySet()),
                      AccessPolicy.Allow(emptySet()),
                      setOf("lemon"),
                      emptyMap(),
                  ),
                  emptyMap(),
                  tmp.resolve("shared").createDirectories(),
                  tmp.resolve("run").createDirectories(),
                  resources = ResourceLink(mapOf("lemon" to "openai-compatible")) { answer },
              )
              .accessors
              .openAiCompatible("lemon")
        }
    val other =
        assertFailsWith<ResourceAccessException> {
          context(mapOf("lemon" to "file")).accessors.openAiCompatible("lemon")
        }
    val asFile = assertFailsWith<ResourceAccessException> { context().accessors.file("lemon") }

    assertEquals(ResourceFailure.NOT_DECLARED, none.failure)
    assertEquals(ResourceFailure.NO_TYPE_DECLARED, nameOnly.failure)
    assertEquals(ResourceFailure.TYPE_MISMATCH, other.failure)
    assertEquals(ResourceFailure.TYPE_MISMATCH, asFile.failure)
  }

  @Test
  fun `a resource the host did not provide is refused`() {
    val e =
        assertFailsWith<ResourceAccessException> {
          context(provided = emptyMap()).accessors.openAiCompatible("lemon")
        }

    assertEquals(ResourceFailure.NOT_PROVIDED, e.failure)
  }

  @Test
  fun `a call is one operation of the host carrying JDK types only`() {
    answer = ok(200, mapOf("content-type" to listOf("application/json")), """{"id":"1"}""")
    val request =
        OpenAiRequest(
            "models.retrieve",
            body = null,
            pathParameters = mapOf("model" to "m1"),
            query = mapOf("limit" to "3"),
            timeouts =
                OpenAiTimeouts(firstByte = Duration.ofSeconds(5), total = Duration.ofMillis(900)),
        )

    val response = context().accessors.openAiCompatible("lemon").call(request)

    val sent = seen.single()
    assertEquals("lemon", sent["resource"])
    assertEquals("openai.call", sent["operation"])
    @Suppress("UNCHECKED_CAST") val arguments = sent["arguments"] as Map<String, Any?>
    assertEquals("models.retrieve", arguments["endpoint"])
    assertEquals(mapOf("model" to "m1"), arguments["pathParameters"])
    assertEquals(mapOf("limit" to "3"), arguments["query"])
    assertEquals(mapOf("firstByte" to 5000L, "total" to 900L), arguments["timeoutsMillis"])
    assertNull(arguments["body"])
    assertEquals(200, response.status)
    assertEquals("""{"id":"1"}""", response.body)
    assertEquals(listOf("application/json"), response.headers["content-type"])
  }

  @Test
  fun `a failure of the host is an exception with the category and the status and nothing else`() {
    answer = mapOf("ok" to false, "failure" to "RATE_LIMITED", "status" to 429, "errorId" to null)

    val e =
        assertFailsWith<ResourceAccessException> {
          context()
              .accessors
              .openAiCompatible("lemon")
              .call(OpenAiRequest("chat.completions", "{}"))
        }

    assertEquals(ResourceFailure.RATE_LIMITED, e.failure)
    assertEquals(429, e.status)
    assertEquals("lemon", e.resource)
  }

  @Test
  fun `a category this copy of core does not know is a plain failure`() {
    answer = mapOf("ok" to false, "failure" to "FROM_THE_FUTURE")

    val e =
        assertFailsWith<ResourceAccessException> {
          context()
              .accessors
              .openAiCompatible("lemon")
              .call(OpenAiRequest("chat.completions", "{}"))
        }

    assertEquals(ResourceFailure.FAILED, e.failure)
  }

  private fun streamAnswer(id: Long = 7L) =
      mapOf(
          "ok" to true,
          "value" to
              mapOf(
                  "stream" to id,
                  "status" to 200,
                  "headers" to mapOf("content-type" to listOf("text/event-stream")),
              ),
      )

  @Test
  fun `a stream is opened with the same arguments as a call, and gives its status and headers`() {
    answer = streamAnswer()
    val request =
        OpenAiRequest(
            "chat.completions",
            body = """{"messages":[]}""",
            timeouts = OpenAiTimeouts(idle = Duration.ofSeconds(2)),
        )

    val stream = context().accessors.openAiCompatible("lemon").stream(request)

    val sent = seen.single()
    assertEquals("lemon", sent["resource"])
    assertEquals("openai.stream.open", sent["operation"])
    @Suppress("UNCHECKED_CAST") val arguments = sent["arguments"] as Map<String, Any?>
    assertEquals("chat.completions", arguments["endpoint"])
    assertEquals("""{"messages":[]}""", arguments["body"])
    assertEquals(mapOf("idle" to 2000L), arguments["timeoutsMillis"])
    assertEquals(200, stream.status)
    assertEquals(listOf("text/event-stream"), stream.headers["content-type"])
  }

  private fun open(): OpenAiStream {
    answer = streamAnswer(7L)
    val stream =
        context()
            .accessors
            .openAiCompatible("lemon")
            .stream(OpenAiRequest("chat.completions", "{}"))
    seen.clear()
    return stream
  }

  private fun next(data: String?) = mapOf("ok" to true, "value" to data)

  @Test
  fun `a pull is one operation of the host with the stream's number, and the end is null and the last call to the host`() {
    val stream = open()
    val events = ArrayDeque(listOf("a", "b", null))
    val pulled = mutableListOf<String?>()
    while (true) {
      answer = next(events.removeFirst())
      val data = stream.next()
      pulled += data
      if (data == null) break
    }

    assertEquals(listOf("a", "b", null), pulled)
    assertEquals(3, seen.size)
    assertTrue(seen.all { it["operation"] == "openai.stream.next" && it["resource"] == "lemon" })
    @Suppress("UNCHECKED_CAST")
    assertEquals(mapOf("stream" to 7L), (seen[0]["arguments"] as Map<String, Any?>))
    assertNull(stream.next())
    assertEquals(3, seen.size, "once it has ended nothing more is asked of the host")
  }

  @Test
  fun `closing a stream asks the host once, never fails, and does nothing for a stream that has ended`() {
    val open = open()
    answer = mapOf("ok" to true, "value" to null)

    open.close()
    open.close()

    assertEquals(1, seen.size)
    assertEquals("openai.stream.close", seen[0]["operation"])
    @Suppress("UNCHECKED_CAST")
    assertEquals(mapOf("stream" to 7L), (seen[0]["arguments"] as Map<String, Any?>))
    assertNull(open.next(), "a closed stream has nothing more")

    val over = open()
    answer = next(null)
    over.next()
    over.close()
    assertEquals(1, seen.size, "the pull that found the end was the only call")

    val gone = open()
    answer = mapOf("ok" to false, "failure" to "ENDED")
    gone.close()
  }

  @Test
  fun `a failed pull is an exception with the category and the status, and so is every later one`() {
    val stream = open()
    answer = mapOf("ok" to false, "failure" to "IDLE_TIMEOUT")

    val first = assertFailsWith<ResourceAccessException> { stream.next() }
    val again = assertFailsWith<ResourceAccessException> { stream.next() }

    assertEquals(ResourceFailure.IDLE_TIMEOUT, first.failure)
    assertEquals(ResourceFailure.IDLE_TIMEOUT, again.failure)
    assertEquals("lemon", first.resource)
    stream.close()
  }

  @Test
  fun `a stream that cannot be opened is an exception with the category and the status`() {
    answer = mapOf("ok" to false, "failure" to "RATE_LIMITED", "status" to 429)

    val e =
        assertFailsWith<ResourceAccessException> {
          context()
              .accessors
              .openAiCompatible("lemon")
              .stream(OpenAiRequest("chat.completions", "{}"))
        }

    assertEquals(ResourceFailure.RATE_LIMITED, e.failure)
    assertEquals(429, e.status)
  }
}
