package samples.views

import dev.lawlan.runline.core.AccessLimit
import dev.lawlan.runline.core.Drill
import dev.lawlan.runline.core.Pipeline
import dev.lawlan.runline.core.PipelineContext
import dev.lawlan.runline.core.PipelineDefinition
import dev.lawlan.runline.core.StatusTone
import dev.lawlan.runline.core.Tone
import dev.lawlan.runline.core.TypedResource
import dev.lawlan.runline.core.View
import dev.lawlan.runline.core.ViewColumn
import dev.lawlan.runline.core.ViewColumnType
import dev.lawlan.runline.core.ViewParam
import dev.lawlan.runline.core.ViewParamType
import dev.lawlan.runline.core.ViewSort

/**
 * SAFE, declares two read-only views for the Console's data section (WI-1302) over the role
 * `view-db`, a `jdbc-pool`: `feeds` (with a drill into `articles`) and `articles` (a parameter that
 * is text, one that is a choice, a status, a time). A run writes the same fixed rows into the
 * database the role comes to, so that what the Console shows, and what the contract tests of the
 * views expect (console/contract/views-contract.ts), is the same every time. One of the titles is
 * markup on purpose: a cell is text and the Console must show it as text.
 */
@PipelineDefinition(
    name = "demo-views",
    network = AccessLimit(allow = []),
    processes = AccessLimit(allow = []),
    typedResources =
        [
            TypedResource(
                name = "view-db",
                type = "jdbc-pool",
                purpose = "Where the demo articles are kept",
            )
        ],
    views =
        [
            View(
                id = "feeds",
                title = "Feeds",
                description = "The feeds and how many articles each has",
                resource = "view-db",
                query = "SELECT name, items FROM feed",
                columns =
                    [
                        ViewColumn(
                            name = "name",
                            label = "Feed",
                            drill = [Drill(view = "articles", parameter = "feed", from = "name")],
                        ),
                        ViewColumn(
                            name = "items",
                            label = "Articles",
                            type = ViewColumnType.INTEGER,
                        ),
                    ],
                sort = [ViewSort(column = "name")],
            ),
            View(
                id = "articles",
                title = "Articles",
                description = "What was fetched, and what became of it",
                resource = "view-db",
                query =
                    "SELECT id, title, state, fetched_at, feed FROM article " +
                        "WHERE (:feed IS NULL OR feed = :feed) AND (:state IS NULL OR state = :state)",
                parameters =
                    [
                        ViewParam(name = "feed", required = false),
                        ViewParam(
                            name = "state",
                            type = ViewParamType.CHOICE,
                            required = false,
                            options = ["kept", "failed", "skipped"],
                        ),
                    ],
                columns =
                    [
                        ViewColumn(name = "id", label = "ID", type = ViewColumnType.INTEGER),
                        ViewColumn(name = "title", label = "Title"),
                        ViewColumn(
                            name = "state",
                            label = "State",
                            type = ViewColumnType.STATUS,
                            tones =
                                [
                                    StatusTone(value = "kept", tone = Tone.POSITIVE),
                                    StatusTone(value = "failed", tone = Tone.NEGATIVE),
                                ],
                        ),
                        ViewColumn(
                            name = "fetched_at",
                            label = "Fetched",
                            type = ViewColumnType.TIME,
                        ),
                        ViewColumn(name = "feed", label = "Feed"),
                    ],
                sort = [ViewSort(column = "fetched_at", descending = true)],
            ),
        ],
)
class ViewsPipeline : Pipeline {
  override fun run(context: PipelineContext) {
    val db = context.accessors.jdbcPool("view-db")
    db.update("CREATE TABLE IF NOT EXISTS feed (name TEXT PRIMARY KEY, items INTEGER NOT NULL)")
    db.update(
        "CREATE TABLE IF NOT EXISTS article (id INTEGER PRIMARY KEY, title TEXT NOT NULL, " +
            "state TEXT NOT NULL, fetched_at TEXT NOT NULL, feed TEXT)"
    )
    db.begin()
    db.update("DELETE FROM feed")
    db.update("DELETE FROM article")
    db.update("INSERT INTO feed (name, items) VALUES (?, ?)", listOf("news", 3))
    db.update("INSERT INTO feed (name, items) VALUES (?, ?)", listOf("blog", 3))
    for ((id, title, state, feed) in ARTICLES) {
      db.update(
          "INSERT INTO article (id, title, state, fetched_at, feed) VALUES (?, ?, ?, ?, ?)",
          listOf(id, title, state, "2026-10-0${id}T08:00:00Z", feed),
      )
    }
    db.commit()
    println("wrote ${ARTICLES.size} articles")
  }

  private data class Article(val id: Int, val title: String, val state: String, val feed: String?)

  private companion object {
    val ARTICLES =
        listOf(
            Article(1, "Alpha launch", "kept", "news"),
            Article(
                2,
                "Beta <b onclick=\"alert(1)\">bold</b> & <script>alert(1)</script>",
                "failed",
                "news",
            ),
            Article(3, "Gamma", "kept", "blog"),
            Article(4, "Delta", "skipped", "blog"),
            Article(5, "Epsilon", "kept", "news"),
            Article(6, "Zeta", "failed", "blog"),
            Article(7, "Eta", "kept", null),
        )
  }
}
