package dev.lawlan.runline.engine.view

import dev.lawlan.runline.engine.support.TestTokens
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*
import kotlinx.serialization.json.*

/** Reading a view (WI-1301): the columns it declares and one page of the rows it selects. */
class ViewReadApiTest : ViewApiSupport() {
  @Test
  fun `a read gives the declared columns and the rows of the page, ordered as declared`() =
      testApplication {
        engineWithRoot()
        defineFeedDb()
        fillArticles()
        val hash = uploadFeed()

        val response = viewRead(hash, query = "?param.state=kept")
        val body = response.expect(HttpStatusCode.OK)

        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertEquals("articles", body.text("view"))
        val columns = body.array("columns")
        assertEquals(listOf("id", "title", "state"), columns.map { it.text("name") })
        assertEquals(listOf("ID", "Title", "State"), columns.map { it.text("label") })
        assertEquals("POSITIVE", columns.last().array("tones").single().text("tone"))
        assertEquals(
            listOf(listOf("1", "alpha", "kept"), listOf("3", "gamma", "kept")),
            body.rows().map { row -> row.map { it.str() } },
        )
        assertEquals(1L, body.rows().first().first().jsonPrimitive.long)
        assertEquals(false, body["hasMore"]!!.jsonPrimitive.boolean)
        assertEquals(50, body["limit"]!!.jsonPrimitive.int)
        assertEquals(0, body["offset"]!!.jsonPrimitive.int)
      }

  private val byId =
      view(
          id = "by-id",
          query =
              "SELECT id, title FROM article WHERE id >= :from AND (:state IS NULL OR state = :again)",
          parameters =
              """@ViewParam(name = "from", type = ViewParamType.INTEGER),
                 @ViewParam(name = "state", type = ViewParamType.CHOICE, required = false, options = {"kept", "failed"}),
                 @ViewParam(name = "again", type = ViewParamType.CHOICE, required = false, options = {"kept", "failed"})""",
          columns =
              """@ViewColumn(name = "id", label = "ID"), @ViewColumn(name = "title", label = "Title")""",
      )

  @Test
  fun `a parameter that is missing, of the wrong type, not an option, repeated or unknown is a 400 that names it and never repeats the value`() =
      testApplication {
        engineWithRoot()
        defineFeedDb()
        fillArticles()
        val hash = uploadFeed(view(), byId)
        val marker = "VALUE-MARKER-7F3A"
        val cases =
            listOf(
                Triple("articles", "", "state" to "missing"),
                Triple("articles", "?param.state=$marker", "state" to "not_an_option"),
                Triple("articles", "?param.state=kept&param.state=failed", "state" to "repeated"),
                Triple("articles", "?param.state=kept&param.$marker=1", marker to "unknown"),
                Triple("by-id", "?param.from=$marker", "from" to "invalid_type"),
                Triple("by-id", "?param.from=1&param.state=$marker", "state" to "not_an_option"),
                Triple("by-id", "?param.from=", "from" to "missing"),
                Triple("articles", "?param.state=kept&limit=0", "limit" to "out_of_range"),
                Triple("articles", "?param.state=kept&limit=201", "limit" to "out_of_range"),
                Triple("articles", "?param.state=kept&limit=$marker", "limit" to "invalid_type"),
                Triple("articles", "?param.state=kept&offset=-1", "offset" to "out_of_range"),
                Triple("articles", "?param.state=kept&offset=$marker", "offset" to "invalid_type"),
            )

        for ((id, query, expected) in cases) {
          val response = viewRead(hash, id, query)

          assertEquals(HttpStatusCode.BadRequest, response.status, query)
          val text = response.bodyAsText()
          val body = Json.parseToJsonElement(text).jsonObject
          assertEquals("invalid_view_parameter", body.text("error"), query)
          assertEquals(expected, body.text("parameter") to body.text("reason"), query)
          if (expected.first != marker) assertFalse(marker in text, "$query: $text")
        }
      }

  @Test
  fun `an optional parameter that is not given is nothing to the query, and an integer is a number`() =
      testApplication {
        engineWithRoot()
        defineFeedDb()
        fillArticles()
        val hash = uploadFeed(view(), byId)

        val all = viewRead(hash, "by-id", "?param.from=2").expect(HttpStatusCode.OK)
        val failed = viewRead(hash, "by-id", "?param.from=1&param.state=failed&param.again=failed")
        val none = viewRead(hash, "by-id", "?param.from=1&param.state=failed&param.again=")

        assertEquals(listOf("beta", "gamma"), all.rows().map { it[1].str() })
        assertEquals(listOf("beta"), failed.expect(HttpStatusCode.OK).rows().map { it[1].str() })
        assertEquals(HttpStatusCode.OK, none.status)
      }

  @Test
  fun `a parameter used twice is one value both times, and a value that looks like a placeholder is only a value`() =
      testApplication {
        engineWithRoot()
        defineFeedDb()
        fillArticles()
        val twice =
            view(
                id = "twice",
                query =
                    "SELECT id, title FROM article WHERE (:q IS NULL OR title <> :q) AND id <> 99 AND ':q' = ':q'",
                parameters = """@ViewParam(name = "q", required = false)""",
                columns =
                    """@ViewColumn(name = "id", label = "ID"), @ViewColumn(name = "title", label = "Title")""",
            )
        val hash = uploadFeed(twice)

        val given = viewRead(hash, "twice", "?param.q=beta").expect(HttpStatusCode.OK)
        val none = viewRead(hash, "twice").expect(HttpStatusCode.OK)
        val lookalike =
            viewRead(hash, "twice", "?param.q=:q%27%3B%20DROP%20TABLE%20article%3B%20--%20%3F")

        assertEquals(listOf("alpha", "gamma"), given.rows().map { it[1].str() })
        assertEquals(3, none.rows().size)
        assertEquals(3, lookalike.expect(HttpStatusCode.OK).rows().size)
        assertEquals(3, viewRead(hash, "twice").expect(HttpStatusCode.OK).rows().size)
      }

  @Test
  fun `pages are cut by limit and offset, say whether more follow, and are 50 rows by default`() =
      testApplication {
        engineWithRoot()
        defineFeedDb()
        sqlite(
            "PRAGMA journal_mode = WAL",
            "CREATE TABLE article (id INTEGER PRIMARY KEY, title TEXT, state TEXT)",
        )
        sqlite(*(1..120).map { "INSERT INTO article VALUES ($it, 't$it', 'kept')" }.toTypedArray())
        val hash = uploadFeed()
        suspend fun pages(query: String) = viewRead(hash, query = "?param.state=kept$query")

        val first = pages("&limit=50&offset=0").expect(HttpStatusCode.OK)
        val default = pages("").expect(HttpStatusCode.OK)
        val second = pages("&limit=50&offset=50").expect(HttpStatusCode.OK)
        val third = pages("&limit=50&offset=100").expect(HttpStatusCode.OK)
        val max = pages("&limit=200").expect(HttpStatusCode.OK)

        assertEquals(first, default)
        assertEquals(50, first.rows().size)
        assertTrue(first["hasMore"]!!.jsonPrimitive.boolean)
        assertTrue(second["hasMore"]!!.jsonPrimitive.boolean)
        assertEquals(20, third.rows().size)
        assertFalse(third["hasMore"]!!.jsonPrimitive.boolean)
        assertEquals(100, third["offset"]!!.jsonPrimitive.int)
        val ids = (first.rows() + second.rows() + third.rows()).map { it[0].jsonPrimitive.long }
        assertEquals((1L..120L).toList(), ids)
        assertEquals(120, max.rows().size)
        assertEquals(false, max["hasMore"]!!.jsonPrimitive.boolean)
        assertFalse("total" in first || "count" in first, "the total is not counted")
      }

  @Test
  fun `a view that is not declared is not found, and a role that cannot be used is told as a run would be`() =
      testApplication {
        engineWithRoot()
        val hash = uploadFeed()
        val unknown = viewRead(hash, "ghost", "?param.state=kept")
        assertEquals(HttpStatusCode.NotFound, unknown.status)
        assertEquals("view_not_found", unknown.json().text("error"))

        val unbound = viewRead(hash, query = "?param.state=kept")
        assertEquals(HttpStatusCode.Conflict, unbound.status)
        val body = unbound.json()
        assertEquals("view_resource_unavailable", body.text("error"))
        assertEquals("unbound", body.text("problem"))
        assertEquals("feed-db", body.text("role"))

        defineFeedDb()
        fillArticles()
        change("feed-db", """{"enabled":false}""")
        assertEquals("disabled", viewRead(hash, query = "?param.state=kept").json().text("problem"))

        remove("feed-db")
        define("feed-db", 1, """"type":"counter"""")
        assertEquals(
            "type_mismatch",
            viewRead(hash, query = "?param.state=kept").json().text("problem"),
        )

        define("elsewhere", 1, """"type":"jdbc-pool","settings":${sqliteSettings()}""")
        client.put("/api/v1/definitions/$hash/feed/resource-bindings/feed-db") {
          bearer(TestTokens.ALICE)()
          contentType(ContentType.Application.Json)
          setBody("""{"resource":"elsewhere"}""")
        }
        assertEquals(HttpStatusCode.OK, viewRead(hash, query = "?param.state=kept").status)
        remove("elsewhere")
        assertEquals("unknown", viewRead(hash, query = "?param.state=kept").json().text("problem"))
      }
}
