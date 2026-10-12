package dev.lawlan.runline.accessors.jdbc

import dev.lawlan.runline.accessors.ReadOnlyException
import dev.lawlan.runline.accessors.ReadOnlyFailure
import dev.lawlan.runline.accessors.ReadOnlyQuery
import dev.lawlan.runline.accessors.ReadOnlyRows
import dev.lawlan.runline.accessors.ReadOrder
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.deleteRecursively
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * What the tests of read-only reads of a SQLite file stand on: a real directory as the resource
 * root, a real file in it that a test fills as another program would, and the real read.
 */
class SqliteViewRig : AutoCloseable {
  val root: Path = Files.createTempDirectory("sqlite-view").toRealPath()
  val profiles = JdbcProfiles(listOf(PostgresProfile, SqliteProfile(root)))
  val file: Path = root.resolve("digest.db")

  fun settings(extra: String = ""): JdbcSettings {
    val json = """{"kind":"sqlite","path":"digest.db"${if (extra.isEmpty()) "" else ",$extra"}}"""
    val parsed = JdbcSettings.parse(Json.parseToJsonElement(json).jsonObject, profiles)
    return (parsed as JdbcSettingsResult.Valid).settings
  }

  /** Runs [sql] on a connection of its own, as the program that fills the file would. */
  fun <T> external(body: (Connection) -> T): T =
      DriverManager.getConnection("jdbc:sqlite:$file").use(body)

  fun execute(vararg statements: String) = external { c ->
    c.createStatement().use { s -> statements.forEach { s.execute(it) } }
  }

  fun scalar(sql: String): String? = external { c ->
    c.createStatement().use { s ->
      s.executeQuery(sql).use { if (it.next()) it.getString(1) else null }
    }
  }

  fun query(
      sql: String,
      columns: List<String>,
      parameters: Map<String, Any?> = emptyMap(),
      order: List<ReadOrder> = emptyList(),
      limit: Int = 50,
      offset: Long = 0,
      extraSettings: String = "",
  ): JdbcViewRead =
      JdbcViewRead(
          profiles.find("sqlite")!!,
          settings(extraSettings),
          JdbcCredential.None,
          ReadOnlyQuery(sql, parameters, columns, order, limit, offset),
      )

  fun read(
      sql: String,
      columns: List<String>,
      parameters: Map<String, Any?> = emptyMap(),
      order: List<ReadOrder> = emptyList(),
      limit: Int = 50,
      offset: Long = 0,
      extraSettings: String = "",
  ): ReadOnlyRows = query(sql, columns, parameters, order, limit, offset, extraSettings).run()

  /** The failure of a read that must fail. */
  fun failure(block: () -> Unit): ReadOnlyFailure {
    try {
      block()
    } catch (e: ReadOnlyException) {
      return e.failure
    }
    fail("the read did not fail")
  }

  fun assertRows(expected: List<List<Any?>>, actual: ReadOnlyRows) =
      assertEquals(expected, actual.rows)

  @OptIn(kotlin.io.path.ExperimentalPathApi::class)
  override fun close() {
    runCatching { root.deleteRecursively() }
  }
}
