package dev.lawlan.runline.engine.view

import dev.lawlan.runline.engine.support.ResourceApiSupport
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlinx.serialization.json.*

/**
 * What the tests of the view API stand on (WI-1301): the whole Engine over a real PostgreSQL, a
 * real directory as the resource root with a real SQLite file in it, pipelines compiled into real
 * jars that declare views and are uploaded through the real endpoint.
 */
abstract class ViewApiSupport : ResourceApiSupport() {
  protected val root: Path = Files.createDirectories(dir.resolve("view-resources"))
  protected val digest: Path = root.resolve("digest.db")

  protected fun ApplicationTestBuilder.engineWithRoot(vararg overrides: Pair<String, String>) =
      engine("resources.root" to "$root", *overrides)

  /** Runs [statements] on the SQLite file as the pipeline's own program would. */
  protected fun sqlite(vararg statements: String) {
    DriverManager.getConnection("jdbc:sqlite:$digest").use { c ->
      c.createStatement().use { s -> statements.forEach { s.execute(it) } }
    }
  }

  protected fun sqliteScalar(sql: String): String? =
      DriverManager.getConnection("jdbc:sqlite:$digest").use { c ->
        c.createStatement().use { s ->
          s.executeQuery(sql).use { if (it.next()) it.getString(1) else null }
        }
      }

  protected fun fillArticles() =
      sqlite(
          "PRAGMA journal_mode = WAL",
          "CREATE TABLE article (id INTEGER PRIMARY KEY, title TEXT, state TEXT)",
          "INSERT INTO article VALUES (1, 'alpha', 'kept'), (2, 'beta', 'failed'), (3, 'gamma', 'kept')",
      )

  protected fun sqliteSettings(extra: String = "") =
      """{"kind":"sqlite","path":"digest.db"${if (extra.isEmpty()) "" else ",$extra"}}"""

  /** Defines the SQLite resource under [name] (the role's own name by default). */
  protected suspend fun ApplicationTestBuilder.defineFeedDb(
      name: String = "feed-db",
      extra: String = "",
  ): HttpResponse = define(name, 1, """"type":"jdbc-pool","settings":${sqliteSettings(extra)}""")

  /** A view over the role `feed-db`; [query] and the rest are written as the annotation does. */
  protected fun view(
      id: String = "articles",
      query: String = "SELECT id, title, state FROM article WHERE state = :state",
      parameters: String =
          """@ViewParam(name = "state", type = ViewParamType.CHOICE, options = {"kept", "failed"})""",
      columns: String =
          """@ViewColumn(name = "id", label = "ID", type = ViewColumnType.INTEGER),
             @ViewColumn(name = "title", label = "Title"),
             @ViewColumn(name = "state", label = "State", type = ViewColumnType.STATUS,
                 tones = {@StatusTone(value = "kept", tone = Tone.POSITIVE)})""",
      sort: String = """@ViewSort(column = "id")""",
      role: String = "feed-db",
  ) =
      """@View(id = "$id", title = "Title of $id", description = "About $id", resource = "$role",
            query = "${query.replace("\"", "\\\"")}",
            parameters = {$parameters}, columns = {$columns}, sort = {$sort})"""

  protected suspend fun ApplicationTestBuilder.uploadFeed(
      vararg views: String = arrayOf(view()),
      name: String = "feed",
  ): String =
      uploaded(
          name,
          types = mapOf("feed-db" to "jdbc-pool"),
          views = views.joinToString(",\n"),
      )

  protected suspend fun ApplicationTestBuilder.viewList(
      hash: String,
      query: String = "",
      pipeline: String = "feed",
      token: String? = dev.lawlan.runline.engine.support.TestTokens.ALICE,
  ) = get("/api/v1/definitions/$hash/$pipeline/views$query", token)

  protected suspend fun ApplicationTestBuilder.viewRead(
      hash: String,
      view: String = "articles",
      query: String = "",
      pipeline: String = "feed",
      token: String? = dev.lawlan.runline.engine.support.TestTokens.ALICE,
  ) = get("/api/v1/definitions/$hash/$pipeline/views/$view$query", token)

  protected fun JsonObject.rows(): List<List<JsonElement>> =
      this["rows"]!!.jsonArray.map { it.jsonArray.toList() }

  protected fun JsonElement.str() = jsonPrimitive.content

  protected suspend fun HttpResponse.expect(status: HttpStatusCode): JsonObject {
    assertEquals(status, this.status, bodyAsText())
    return json()
  }
}
