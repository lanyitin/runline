package dev.lawlan.runline.engine.view

import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*
import kotlinx.serialization.json.*

/**
 * What a view can never do, and the answers when a read cannot be given (WI-1301, ADR-033): the
 * Engine keeps a view from writing whatever its query says and whatever the account may do, and a
 * failure of a read is one of the named ones, not a 500.
 */
class ViewSafetyApiTest : ViewApiSupport() {
  private fun only(id: String, query: String) =
      view(
          id = id,
          query = query,
          parameters = "",
          columns = """@ViewColumn(name = "title", label = "Title")""",
          sort = "",
      )

  private val hostile =
      mapOf(
          "insert" to "INSERT INTO article VALUES (9, 'new', 'kept')",
          "update" to "UPDATE article SET title = 'changed'",
          "delete" to "DELETE FROM article",
          "drop" to "DROP TABLE article",
          "create" to "CREATE TABLE other (a INTEGER)",
          "cte" to "WITH gone AS (SELECT 1) DELETE FROM article",
          "multi" to "SELECT title FROM article; DELETE FROM article",
          "hidden" to "SELECT title FROM article) ; DELETE FROM article WHERE (1 = 1",
          "pragma" to "PRAGMA query_only = OFF",
          "writable" to "PRAGMA query_only = OFF; DELETE FROM article",
          "attach" to "ATTACH DATABASE ':memory:' AS other",
      )

  @Test
  fun `a query that writes, runs more than one statement or changes the connection is refused and nothing changes`() =
      testApplication {
        engineWithRoot()
        defineFeedDb()
        fillArticles()
        val hash = uploadFeed(*hostile.map { (id, sql) -> only(id, sql) }.toTypedArray())

        for (id in hostile.keys) {
          val response = viewRead(hash, id)

          assertEquals(HttpStatusCode.UnprocessableEntity, response.status, id)
          assertEquals("view_query_rejected", response.json().text("error"), id)
        }

        assertEquals(
            "alpha,beta,gamma",
            sqliteScalar("SELECT group_concat(title) FROM (SELECT title FROM article ORDER BY id)"),
        )
        assertEquals("wal", sqliteScalar("PRAGMA journal_mode"))
        assertEquals(
            "article",
            sqliteScalar("SELECT group_concat(name) FROM sqlite_master WHERE type = 'table'"),
        )
      }

  @Test
  fun `a query that names what the file does not have is a mismatch of structure, also when the file is not there yet`() =
      testApplication {
        engineWithRoot()
        defineFeedDb()
        val hash =
            uploadFeed(
                only("missing-table", "SELECT title FROM no_such_table"),
                only("missing-column", "SELECT no_such_column AS title FROM article"),
                only("fine", "SELECT title FROM article"),
            )

        val noFile = viewRead(hash, "fine")
        fillArticles()
        val table = viewRead(hash, "missing-table")
        val column = viewRead(hash, "missing-column")

        for (response in listOf(noFile, table, column)) {
          assertEquals(HttpStatusCode.Conflict, response.status)
          assertEquals("view_schema_mismatch", response.json().text("error"))
        }
        assertEquals(HttpStatusCode.OK, viewRead(hash, "fine").status)
      }

  @Test
  fun `more rows or bytes than the resource allows is too large, and a statement that takes too long times out`() =
      testApplication {
        engineWithRoot()
        defineFeedDb(extra = """"maxRows":2,"maxResponseBytes":40,"timeouts":{"statementMs":500}""")
        fillArticles()
        val forever =
            "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c) SELECT count(*) AS title FROM c"
        val hash =
            uploadFeed(
                only("rows", "SELECT title FROM article"),
                only(
                    "bytes",
                    "SELECT '${"x".repeat(30)}' AS title UNION ALL SELECT '${"y".repeat(30)}'",
                ),
                only("few", "SELECT title FROM article LIMIT 2"),
                only("forever", forever),
            )

        val rows = viewRead(hash, "rows")
        val bytes = viewRead(hash, "bytes")
        val few = viewRead(hash, "few")
        val started = System.nanoTime()
        val slow = viewRead(hash, "forever")
        val tookMillis = (System.nanoTime() - started) / 1_000_000

        assertEquals(HttpStatusCode.UnprocessableEntity, rows.status)
        assertEquals("view_result_too_large", rows.json().text("error"))
        assertEquals("view_result_too_large", bytes.json().text("error"))
        assertEquals(HttpStatusCode.OK, few.status)
        assertEquals(HttpStatusCode.GatewayTimeout, slow.status)
        assertEquals("view_timeout", slow.json().text("error"))
        assertTrue(tookMillis < 5_000, "the statement was not stopped: $tookMillis ms")
      }

  @Test
  fun `a read of a SQLite file ends after 30 seconds although the statement limit of the resource is far longer`() =
      testApplication {
        engineWithRoot()
        defineFeedDb()
        fillArticles()
        val forever =
            "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c) SELECT count(*) AS title FROM c"
        val hash = uploadFeed(only("forever", forever), only("quick", "SELECT title FROM article"))

        val started = System.nanoTime()
        val slow = viewRead(hash, "forever")
        val tookMillis = (System.nanoTime() - started) / 1_000_000

        assertEquals(HttpStatusCode.GatewayTimeout, slow.status)
        assertEquals("view_timeout", slow.json().text("error"))
        assertTrue(tookMillis in 29_000..40_000, "took $tookMillis ms")
        // The place of the read is back, and the file is usable at once.
        assertEquals(HttpStatusCode.OK, viewRead(hash, "quick").status)
        sqlite("INSERT INTO article VALUES (4, 'delta', 'kept')")
      }
}
