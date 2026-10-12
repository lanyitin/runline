package dev.lawlan.runline.engine.view

import io.ktor.http.*
import io.ktor.server.testing.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*

/**
 * A view read while a run has the same SQLite file and the same resource (WI-1301, ADR-033): the
 * read takes no place of the run, waits for nothing, and the run's write does not fail for it.
 */
class ViewAlongsideRunApiTest : ViewApiSupport() {
  private val shared: Path = Files.createDirectories(dir.resolve("shared"))

  private val holdAndWrite =
      """
      JdbcAccessor db = context.getAccessors().jdbcPool("feed-db");
      db.update("CREATE TABLE IF NOT EXISTS article (id INTEGER PRIMARY KEY, title TEXT, state TEXT)");
      db.update("INSERT OR IGNORE INTO article VALUES (1, 'alpha', 'kept')");
      db.begin();
      db.update("INSERT INTO article VALUES (2, 'held', 'kept')");
      context.getFiles().writeText(FileScope.PIPELINE_SHARED, "held", "x");
      try { while (!context.getFiles().exists(FileScope.PIPELINE_SHARED, "release")) Thread.sleep(10); }
      catch (InterruptedException e) { throw new RuntimeException(e); }
      db.commit();
      """
          .trimIndent()

  private suspend fun awaitFile(file: Path) {
    val deadline = System.nanoTime() + 30_000_000_000
    while (!Files.exists(file)) {
      check(System.nanoTime() < deadline) { "$file did not appear" }
      kotlinx.coroutines.delay(20)
    }
  }

  @Test
  fun `while a run holds the resource and the write lock, a view reads what is committed and the run commits`() =
      testApplication {
        engineWithRoot("workspace.sharedRoot" to "$shared")
        defineFeedDb()
        val hash =
            uploaded(
                "feed",
                types = mapOf("feed-db" to "jdbc-pool"),
                body = holdAndWrite,
                views = view(),
            )
        val runId = start(hash, "feed")
        awaitFile(shared.resolve("feed").resolve("held"))
        val before = resource("feed-db")

        val started = System.nanoTime()
        val body = viewRead(hash, query = "?param.state=kept").expect(HttpStatusCode.OK)
        val millis = (System.nanoTime() - started) / 1_000_000
        val during = resource("feed-db")

        assertEquals(listOf("alpha"), body.rows().map { it[1].str() }, "only what is committed")
        assertTrue(millis < 5_000, "the read waited $millis ms")
        assertEquals(1, before.array("holders").size)
        assertEquals(
            before.array("holders").map { it.text("runId") },
            during.array("holders").map { it.text("runId") },
        )
        assertEquals(emptyList(), during.array("waiters"))
        assertEquals(before["usage"], during["usage"])
        Files.writeString(shared.resolve("feed").resolve("release"), "x")
        assertEquals("SUCCEEDED", awaitState(runId, "SUCCEEDED", "FAILED").text("state"))
        val after = viewRead(hash, query = "?param.state=kept").expect(HttpStatusCode.OK)
        assertEquals(listOf("alpha", "held"), after.rows().map { it[1].str() })
      }
}
