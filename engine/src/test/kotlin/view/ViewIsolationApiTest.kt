package dev.lawlan.runline.engine.view

import dev.lawlan.runline.engine.support.TestTokens
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*
import kotlinx.serialization.json.*

/**
 * Whose versions a view can be read through (WI-1301, ADR-020, ADR-025): the same rules as for the
 * bindings of resources, and a version that is another's cannot be told from one that is not there.
 */
class ViewIsolationApiTest : ViewApiSupport() {
  @Test
  fun `another uploader's version, one that is not there and a view that is not there are one answer, byte for byte`() =
      testApplication {
        engineWithRoot()
        defineFeedDb()
        fillArticles()
        val hash = uploadFeed()
        val read = "?param.state=kept"

        val others = viewRead(hash, query = read, token = TestTokens.BOB)
        val missing = viewRead("0".repeat(64), query = read, token = TestTokens.BOB)
        val noPipeline = viewRead(hash, pipeline = "ghost", query = read)
        val namedOwner = viewRead(hash, query = "$read&uploader=alice", token = TestTokens.BOB)
        // The caller's own version, with a view that is not there, is a different matter.
        val ownGhost = viewRead(hash, "ghost", read)

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
        assertEquals("view_not_found", ownGhost.json().text("error"))
        // Whatever Bob puts in the parameters, nothing tells him that the version is there.
        val withBadParameters =
            viewRead(hash, query = "?param.zzz=1&limit=0", token = TestTokens.BOB)
        assertEquals(expected, withBadParameters.bodyAsText())
      }

  @Test
  fun `an administrator reads any uploader's version by naming the uploader`() = testApplication {
    engineWithRoot()
    defineFeedDb()
    fillArticles()
    val bytes = jarBytes("feed", types = mapOf("feed-db" to "jdbc-pool"), views = view())
    val hash = uploadBytes(bytes, TestTokens.ALICE).json().text("contentHash")
    val read = "?param.state=kept"

    val own = viewRead(hash, query = read, token = TestTokens.ALICE)
    val ambiguous = viewRead(hash, query = read, token = TestTokens.ROOT)
    uploadBytes(bytes, TestTokens.BOB)
    val several = viewRead(hash, query = read, token = TestTokens.ROOT)
    val named = viewRead(hash, query = "$read&uploader=bob", token = TestTokens.ROOT)

    assertEquals(HttpStatusCode.OK, own.status)
    assertEquals(HttpStatusCode.OK, ambiguous.status)
    assertEquals(HttpStatusCode.Conflict, several.status)
    assertEquals("ambiguous_version", several.json().text("error"))
    assertEquals(HttpStatusCode.OK, named.status)
    assertEquals(HttpStatusCode.Unauthorized, viewRead(hash, query = read, token = null).status)
  }

  @Test
  fun `the bindings of the uploader decide which resource a view reads, and nobody else's reach it`() =
      testApplication {
        engineWithRoot()
        defineFeedDb()
        fillArticles()
        define(
            "empty-db",
            1,
            """"type":"jdbc-pool","settings":{"kind":"sqlite","path":"empty.db"}""",
        )
        java.sql.DriverManager.getConnection("jdbc:sqlite:${root.resolve("empty.db")}").use { c ->
          c.createStatement().use {
            it.execute("PRAGMA journal_mode = WAL")
            it.execute("CREATE TABLE article (id INTEGER PRIMARY KEY, title TEXT, state TEXT)")
          }
        }
        val bytes = jarBytes("feed", types = mapOf("feed-db" to "jdbc-pool"), views = view())
        val hash = uploadBytes(bytes, TestTokens.ALICE).json().text("contentHash")
        uploadBytes(bytes, TestTokens.BOB)
        val read = "?param.state=kept&uploader="
        suspend fun rows(uploader: String) =
            viewRead(hash, query = read + uploader, token = TestTokens.ROOT)
                .expect(HttpStatusCode.OK)
                .rows()
                .size

        client.put("/api/v1/definitions/$hash/feed/resource-bindings/feed-db?uploader=bob") {
          header(HttpHeaders.Authorization, "Bearer ${TestTokens.ROOT}")
          contentType(ContentType.Application.Json)
          setBody("""{"resource":"empty-db"}""")
        }

        assertEquals(2, rows("alice"))
        assertEquals(0, rows("bob"))
      }
}
