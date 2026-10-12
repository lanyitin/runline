package dev.lawlan.runline.engine.view

import dev.lawlan.runline.engine.support.TestTokens
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*
import kotlinx.serialization.json.*

/**
 * Listing the views of a pipeline version (WI-1301): what they declare and whether they can run.
 */
class ViewListApiTest : ViewApiSupport() {
  @Test
  fun `the list gives each declared view with its definition and that it can be read, and runs no query`() =
      testApplication {
        engineWithRoot()
        defineFeedDb()
        val hash =
            uploadFeed(
                view(),
                view(
                    id = "failures",
                    query = "SELECT id, title, state FROM article",
                    parameters = "",
                ),
            )

        val body = viewList(hash).expect(HttpStatusCode.OK)

        assertEquals(hash, body.text("contentHash"))
        assertEquals("alice", body.text("uploader"))
        assertEquals("feed", body.text("pipeline"))
        val views = body.array("views")
        assertEquals(listOf("articles", "failures"), views.map { it.text("id") })
        val articles = views.first()
        assertEquals("Title of articles", articles.text("title"))
        assertEquals("About articles", articles.text("description"))
        assertEquals("feed-db", articles.text("resource"))
        assertEquals("ready", articles.text("status"))
        val parameter = articles.array("parameters").single()
        assertEquals(
            listOf("state", "CHOICE", "true"),
            listOf("name", "type", "required").map { parameter.text(it) },
        )
        assertEquals(listOf("kept", "failed"), parameter["options"]!!.jsonArray.map { it.str() })
        assertEquals(
            listOf("id", "title", "state"),
            articles.array("columns").map { it.text("name") },
        )
        assertEquals("STATUS", articles.array("columns").last().text("type"))
        assertEquals("id", articles.array("sort").single().text("column"))
        // Nothing was run: the file the views read has not even been made.
        assertFalse(java.nio.file.Files.exists(digest))
      }

  @Test
  fun `each view says why it cannot be read, in the words a run is refused with`() =
      testApplication {
        engineWithRoot()
        val hash = uploadFeed()
        fun status(body: JsonObject) = body.array("views").single().text("status")

        assertEquals("unbound", status(viewList(hash).expect(HttpStatusCode.OK)))

        defineFeedDb()
        assertEquals("ready", status(viewList(hash).expect(HttpStatusCode.OK)))

        change("feed-db", """{"enabled":false}""")
        assertEquals("disabled", status(viewList(hash).expect(HttpStatusCode.OK)))

        remove("feed-db")
        define("feed-db", 1, """"type":"counter"""")
        assertEquals("type_mismatch", status(viewList(hash).expect(HttpStatusCode.OK)))

        define("elsewhere", 1, """"type":"jdbc-pool","settings":${sqliteSettings()}""")
        client.put("/api/v1/definitions/$hash/feed/resource-bindings/feed-db") {
          bearer(TestTokens.ALICE)()
          contentType(ContentType.Application.Json)
          setBody("""{"resource":"elsewhere"}""")
        }
        assertEquals("ready", status(viewList(hash).expect(HttpStatusCode.OK)))
        remove("elsewhere")
        assertEquals("unknown", status(viewList(hash).expect(HttpStatusCode.OK)))
      }

  @Test
  fun `a pipeline without views lists none`() = testApplication {
    engineWithRoot()
    val hash = uploaded("plain")

    val body = viewList(hash, pipeline = "plain").expect(HttpStatusCode.OK)

    assertEquals(emptyList(), body.array("views"))
  }

  @Test
  fun `another uploader's version, a version that is not there and a pipeline that is not there are one answer`() =
      testApplication {
        engineWithRoot()
        val hash = uploadFeed()

        val others = viewList(hash, token = TestTokens.BOB)
        val missing = viewList("0".repeat(64), token = TestTokens.BOB)
        val noPipeline = viewList(hash, pipeline = "ghost", token = TestTokens.ALICE)
        val namedOwner = viewList(hash, "?uploader=alice", token = TestTokens.BOB)

        assertEquals(HttpStatusCode.NotFound, others.status)
        val expected = missing.bodyAsText()
        assertEquals(
            "definition_not_found",
            Json.parseToJsonElement(expected).jsonObject.text("error"),
        )
        assertEquals(expected, others.bodyAsText())
        assertEquals(expected, noPipeline.bodyAsText())
        assertEquals(expected, namedOwner.bodyAsText())
        assertEquals(others.contentType(), missing.contentType())
      }

  @Test
  fun `an administrator names the uploader when several uploaded the content`() = testApplication {
    engineWithRoot()
    val bytes = jarBytes("feed", types = mapOf("feed-db" to "jdbc-pool"), views = view())
    val hash = uploadBytes(bytes, TestTokens.ALICE).json().text("contentHash")
    uploadBytes(bytes, TestTokens.BOB)

    val ambiguous = viewList(hash, token = TestTokens.ROOT)
    val alices = viewList(hash, "?uploader=alice", token = TestTokens.ROOT)
    val bobs = viewList(hash, "?uploader=bob", token = TestTokens.ROOT)

    assertEquals(HttpStatusCode.Conflict, ambiguous.status)
    assertEquals("ambiguous_version", ambiguous.json().text("error"))
    assertEquals("alice", alices.expect(HttpStatusCode.OK).text("uploader"))
    assertEquals("bob", bobs.expect(HttpStatusCode.OK).text("uploader"))
  }

  @Test
  fun `a caller without a token is turned away`() = testApplication {
    engineWithRoot()
    val hash = uploadFeed()

    assertEquals(HttpStatusCode.Unauthorized, viewList(hash, token = null).status)
  }
}
