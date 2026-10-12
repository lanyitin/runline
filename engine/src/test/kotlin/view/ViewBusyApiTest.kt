package dev.lawlan.runline.engine.view

import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/** The limit on reads at once (WI-1301, ADR-033): per resource, and apart from the runs'. */
class ViewBusyApiTest : ViewApiSupport() {
  private val forever =
      "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c) SELECT count(*) AS title FROM c"

  private fun only(id: String, query: String) =
      view(
          id = id,
          query = query,
          parameters = "",
          columns = """@ViewColumn(name = "title", label = "Title")""",
          sort = "",
      )

  @Test
  fun `reads beyond the limit of a resource are told it is busy at once, and others go on`() =
      testApplication {
        engineWithRoot()
        defineFeedDb(extra = """"timeouts":{"statementMs":2500}""")
        define("other-db", 1, """"type":"jdbc-pool","settings":${sqliteSettings()}""")
        fillArticles()
        val hash =
            uploaded(
                "feed",
                types = mapOf("feed-db" to "jdbc-pool", "other-db" to "jdbc-pool"),
                views =
                    listOf(
                            only("forever", forever),
                            only("quick", "SELECT title FROM article"),
                            only("elsewhere", "SELECT title FROM article")
                                .replace("feed-db", "other-db"),
                        )
                        .joinToString(","),
            )
        val limit = dev.lawlan.runline.engine.config.ViewSettings.DEFAULT_MAX_READS_PER_RESOURCE

        coroutineScope {
          val running = (1..limit).map { async { viewRead(hash, "forever") } }
          delay(700)
          val refused = viewRead(hash, "quick")

          assertEquals(HttpStatusCode.TooManyRequests, refused.status)
          assertEquals("view_busy", refused.json().text("error"))
          // Another resource has reads of its own.
          assertEquals(HttpStatusCode.OK, viewRead(hash, "elsewhere").status)
          val ended = running.awaitAll()
          assertTrue(ended.all { it.status == HttpStatusCode.GatewayTimeout })
        }
        // The slots come back when the reads end.
        assertEquals(HttpStatusCode.OK, viewRead(hash, "quick").status)
      }

  @Test
  fun `the limit that is configured is the limit of every resource, and its places come back`() =
      testApplication {
        engineWithRoot("views.maxReadsPerResource" to "2")
        defineFeedDb(extra = """"timeouts":{"statementMs":2500}""")
        define(
            "other-db",
            1,
            """"type":"jdbc-pool","settings":${sqliteSettings(""""timeouts":{"statementMs":2500}""")}""",
        )
        fillArticles()
        val hash =
            uploaded(
                "feed",
                types = mapOf("feed-db" to "jdbc-pool", "other-db" to "jdbc-pool"),
                views =
                    listOf(
                            only("forever", forever),
                            only("foreverElsewhere", forever).replace("feed-db", "other-db"),
                            only("quick", "SELECT title FROM article"),
                        )
                        .joinToString(","),
            )

        coroutineScope {
          val running = (1..2).map { async { viewRead(hash, "forever") } }
          val elsewhere = async { viewRead(hash, "foreverElsewhere") }
          delay(700)
          val refused = viewRead(hash, "quick")

          assertEquals(HttpStatusCode.TooManyRequests, refused.status)
          assertEquals("view_busy", refused.json().text("error"))
          assertEquals(HttpStatusCode.GatewayTimeout, elsewhere.await().status)
          assertTrue(running.awaitAll().all { it.status == HttpStatusCode.GatewayTimeout })
        }
        assertEquals(HttpStatusCode.OK, viewRead(hash, "quick").status)
      }
}
