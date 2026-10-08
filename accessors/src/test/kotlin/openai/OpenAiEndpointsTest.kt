package dev.lawlan.runline.accessors.openai

import dev.lawlan.runline.accessors.ResourceOperationFailure
import dev.lawlan.runline.core.ResourceFailure
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The endpoint catalog (ADR-019 decision 13, WI-46): what a pipeline may name, and the proof that
 * nothing a pipeline gives for the path or the query can change where a request goes.
 */
class OpenAiEndpointsTest {
  private fun endpoint(id: String) = checkNotNull(OpenAiEndpoints.find(id)) { id }

  @Test
  fun `the catalog holds every entry of the ADR with its method and path`() {
    val expected =
        mapOf(
            "chat.completions" to ("POST" to "/chat/completions"),
            "completions" to ("POST" to "/completions"),
            "embeddings" to ("POST" to "/embeddings"),
            "models.list" to ("GET" to "/models"),
            "models.retrieve" to ("GET" to "/models/{model}"),
            "responses.create" to ("POST" to "/responses"),
            "responses.retrieve" to ("GET" to "/responses/{id}"),
            "responses.delete" to ("DELETE" to "/responses/{id}"),
            "responses.cancel" to ("POST" to "/responses/{id}/cancel"),
            "responses.input_items" to ("GET" to "/responses/{id}/input_items"),
            "moderations" to ("POST" to "/moderations"),
            "rerank" to ("POST" to "/rerank"),
            "reranking" to ("POST" to "/reranking"),
            "images.generations" to ("POST" to "/images/generations"),
            "images.edits" to ("POST" to "/images/edits"),
            "images.variations" to ("POST" to "/images/variations"),
            "audio.speech" to ("POST" to "/audio/speech"),
            "audio.transcriptions" to ("POST" to "/audio/transcriptions"),
            "audio.translations" to ("POST" to "/audio/translations"),
            "files.create" to ("POST" to "/files"),
            "files.list" to ("GET" to "/files"),
            "files.retrieve" to ("GET" to "/files/{id}"),
            "files.delete" to ("DELETE" to "/files/{id}"),
            "files.content" to ("GET" to "/files/{id}/content"),
            "batches.create" to ("POST" to "/batches"),
            "batches.list" to ("GET" to "/batches"),
            "batches.retrieve" to ("GET" to "/batches/{id}"),
            "batches.cancel" to ("POST" to "/batches/{id}/cancel"),
        )

    assertEquals(expected.keys, OpenAiEndpoints.all.map { it.id }.toSet())
    assertEquals(OpenAiEndpoints.all.size, OpenAiEndpoints.all.map { it.id }.toSet().size)
    for ((id, shape) in expected) {
      assertEquals(shape, endpoint(id).method to endpoint(id).path, id)
    }
  }

  @Test
  fun `only chat, completions, embeddings and the models are enabled by default`() {
    assertEquals(
        setOf("chat.completions", "completions", "embeddings", "models.list", "models.retrieve"),
        OpenAiEndpoints.all.filter { it.defaultEnabled }.map { it.id }.toSet(),
    )
    assertEquals(
        OpenAiEndpoints.all.filter { it.defaultEnabled }.map { it.id },
        OpenAiEndpoints.defaultEnabled,
    )
  }

  @Test
  fun `this version carries out every entry of the catalog`() {
    // An entry a later version adds before it works is not delivered, and cannot be enabled.
    assertEquals(emptySet(), OpenAiEndpoints.all.filterNot { it.delivered }.map { it.id }.toSet())
  }

  @Test
  fun `each entry is in its group of the ADR`() {
    val groups =
        mapOf(
            "chat" to listOf("chat.completions"),
            "completions" to listOf("completions"),
            "embeddings" to listOf("embeddings"),
            "models" to listOf("models.list", "models.retrieve"),
            "responses" to
                listOf(
                    "responses.create",
                    "responses.retrieve",
                    "responses.delete",
                    "responses.cancel",
                    "responses.input_items",
                ),
            "moderations" to listOf("moderations"),
            "rerank" to listOf("rerank", "reranking"),
            "images" to listOf("images.generations", "images.edits", "images.variations"),
            "audio" to listOf("audio.speech", "audio.transcriptions", "audio.translations"),
            "files" to
                listOf(
                    "files.create",
                    "files.list",
                    "files.retrieve",
                    "files.delete",
                    "files.content",
                ),
            "batches" to
                listOf("batches.create", "batches.list", "batches.retrieve", "batches.cancel"),
        )

    assertEquals(
        groups,
        OpenAiEndpoints.all.groupBy({ it.group }, { it.id }),
    )
  }

  @Test
  fun `the stateful entries are those that make, cancel or delete what the service keeps`() {
    assertEquals(
        setOf(
            "responses.delete",
            "responses.cancel",
            "files.create",
            "files.delete",
            "batches.create",
            "batches.cancel",
        ),
        OpenAiEndpoints.all.filter { it.stateful }.map { it.id }.toSet(),
    )
    // The default posture is "only generating": nothing stateful is enabled by default.
    assertTrue(OpenAiEndpoints.all.none { it.stateful && it.defaultEnabled })
  }

  @Test
  fun `the entries that can be enabled are the delivered ones, in catalog order`() {
    assertEquals(OpenAiEndpoints.all.filter { it.delivered }, OpenAiEndpoints.enableable)
  }

  @Test
  fun `an unknown name is not an endpoint`() {
    assertNull(OpenAiEndpoints.find("chat/completions"))
    assertNull(OpenAiEndpoints.find("CHAT.COMPLETIONS"))
    assertNull(OpenAiEndpoints.find(""))
  }

  @Test
  fun `a path parameter is put into the path and nothing else is`() {
    assertEquals(
        "/models/Qwen3-0.6B_q4:latest",
        endpoint("models.retrieve").pathFor(mapOf("model" to "Qwen3-0.6B_q4:latest")),
    )
    assertEquals(
        "/files/file-abc123/content",
        endpoint("files.content").pathFor(mapOf("id" to "file-abc123")),
    )
    assertEquals("/chat/completions", endpoint("chat.completions").pathFor(emptyMap()))
  }

  @Test
  fun `a path parameter with a separator, a relative segment, a control character or anything unusual is refused`() {
    val hostile =
        listOf(
            "a/b",
            "../x",
            "..",
            ".",
            "a\\b",
            "a b",
            "a%2fb",
            "a%2e%2e",
            "a?b=c",
            "a#b",
            "a\nb",
            "a\u0000b",
            "a\tb",
            "",
            "x".repeat(257),
            "ü",
            "a;b",
            "a@b",
            "http://evil.example/",
            "//evil.example",
        )

    for (value in hostile) {
      val e =
          assertFailsWith<ResourceOperationFailure>("'$value'") {
            endpoint("models.retrieve").pathFor(mapOf("model" to value))
          }
      assertEquals(ResourceFailure.INVALID_ARGUMENT, e.failure, "'$value'")
    }
  }

  @Test
  fun `the parameters given must be exactly those the entry has`() {
    for (given in listOf(emptyMap(), mapOf("model" to "a", "extra" to "b"), mapOf("id" to "a"))) {
      val e =
          assertFailsWith<ResourceOperationFailure> { endpoint("models.retrieve").pathFor(given) }
      assertEquals(ResourceFailure.INVALID_ARGUMENT, e.failure)
    }
    assertFailsWith<ResourceOperationFailure> {
      endpoint("models.list").pathFor(mapOf("model" to "a"))
    }
  }

  @Test
  fun `a query parameter is accepted only when the entry lists it, and is encoded`() {
    assertEquals("", endpoint("files.list").queryFor(emptyMap()))
    assertEquals(
        "?limit=10&purpose=batch%26x%3D1%20y",
        endpoint("files.list").queryFor(linkedMapOf("limit" to "10", "purpose" to "batch&x=1 y")),
    )

    val e =
        assertFailsWith<ResourceOperationFailure> {
          endpoint("files.list").queryFor(mapOf("api_key" to "x"))
        }
    assertEquals(ResourceFailure.INVALID_ARGUMENT, e.failure)
    assertFailsWith<ResourceOperationFailure> {
      endpoint("models.list").queryFor(mapOf("limit" to "1"))
    }
    assertFailsWith<ResourceOperationFailure> {
      endpoint("files.list").queryFor(mapOf("limit" to "a\nb"))
    }
  }

  @Test
  fun `whatever a pipeline gives for the path and the query, the request goes to the same host, port and method`() {
    val base = URI.create("http://service.internal:8081/api/v1")
    val hostile =
        listOf(
            "a/b",
            "../../x",
            "..",
            "//evil.example/p",
            "a@evil.example",
            "a b",
            "%2f",
            "x?y=z",
            "\u0000",
        )

    for (entry in OpenAiEndpoints.all.filter { it.delivered }) {
      for (value in hostile) {
        val path =
            try {
              entry.pathFor(entry.pathParameters.associateWith { value })
            } catch (e: ResourceOperationFailure) {
              continue // refused: it goes nowhere
            }
        val query =
            try {
              entry.queryFor(entry.queryParameters.associateWith { value })
            } catch (e: ResourceOperationFailure) {
              ""
            }
        val uri = URI.create(base.toString() + path + query)

        assertEquals("service.internal", uri.host, entry.id)
        assertEquals(8081, uri.port, entry.id)
        assertEquals("http", uri.scheme, entry.id)
        assertTrue(uri.rawPath.startsWith("/api/v1/"), "${entry.id}: ${uri.rawPath}")
        assertNull(uri.rawUserInfo, entry.id)
        assertNull(uri.rawFragment, entry.id)
        // The path has exactly the segments the template has.
        assertEquals(
            entry.path.count { it == '/' },
            uri.rawPath.removePrefix("/api/v1").count { it == '/' },
            "${entry.id}: ${uri.rawPath}",
        )
        assertNotNull(entry.method)
      }
    }
  }
}
