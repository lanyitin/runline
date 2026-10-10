package dev.lawlan.runline.accessors.jdbc

import dev.lawlan.runline.accessors.BoundResources
import dev.lawlan.runline.accessors.Invalidation
import dev.lawlan.runline.core.ResourceFailure
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * What the tests of `sqlite` accessors stand on (WI-1003): a real directory as the resource root, a
 * real SQLite file in it, the pools of the host and a [BoundResources] that a run's calls go
 * through, as the Engine's do. Nothing stands in for the database or the file system.
 */
class SqliteRig : AutoCloseable {
  val root: Path = Files.createTempDirectory("sqlite-root").toRealPath()
  val profiles = JdbcProfiles(listOf(PostgresProfile, SqliteProfile(root)))
  val pools = JdbcPools(profiles)
  val observer = RecordingObserver()
  private val hosts = mutableListOf<BoundResources>()

  /** A host with one `jdbc-pool` resource called [name], as an administrator defined it. */
  fun host(
      path: String = "digest.db",
      extra: String = "",
      capacity: Int = 1,
      name: String = "db",
  ): BoundResources {
    val binding = pools.bind(name, settings(path, extra), JdbcCredential.None, capacity, observer)
    return BoundResources(mapOf(name to binding)).also { hosts += it }
  }

  fun settings(path: String = "digest.db", extra: String = ""): JdbcSettings {
    val json = """{"kind":"sqlite","path":"$path"${if (extra.isEmpty()) "" else ",$extra"}}"""
    val parsed = JdbcSettings.parse(Json.parseToJsonElement(json).jsonObject, profiles)
    return (parsed as JdbcSettingsResult.Valid).settings
  }

  /** The answer of one call, as the host gives it to a run. */
  fun call(
      host: BoundResources,
      operation: String,
      arguments: Map<String, Any?> = emptyMap(),
      resource: String = "db",
  ): Map<String, Any?> =
      host.call(
          mapOf("resource" to resource, "operation" to operation, "arguments" to HashMap(arguments))
      )

  /** The value of a call that succeeded; a [Failed] when it did not. */
  fun value(
      host: BoundResources,
      operation: String,
      arguments: Map<String, Any?> = emptyMap(),
  ): Any? {
    val answer = call(host, operation, arguments)
    if (answer["ok"] == true) return answer["value"]
    throw Failed(ResourceFailure.valueOf(answer["failure"] as String), answer)
  }

  /** The rows of a query, each as its columns by name. */
  fun query(
      host: BoundResources,
      sql: String,
      parameters: List<Any?> = emptyList(),
  ): List<Map<String, Any?>> {
    val answer = value(host, "jdbc.query", statement(sql, parameters)) as Map<*, *>
    val columns = (answer["columns"] as List<*>).map { it as String }
    return (answer["rows"] as List<*>).map { row ->
      columns.zip(row as List<*>).toMap(LinkedHashMap())
    }
  }

  fun update(host: BoundResources, sql: String, parameters: List<Any?> = emptyList()): Long =
      value(host, "jdbc.update", statement(sql, parameters)) as Long

  fun statement(sql: String, parameters: List<Any?> = emptyList()): Map<String, Any?> =
      mapOf("sql" to sql, "parameters" to ArrayList(parameters))

  /** Opens the file as another program would, on a connection of its own, and runs [body]. */
  fun <T> external(
      path: String = "digest.db",
      readOnly: Boolean = false,
      body: (java.sql.Connection) -> T,
  ): T {
    val config = org.sqlite.SQLiteConfig().apply { if (readOnly) setReadOnly(true) }
    return java.sql.DriverManager.getConnection(
            "jdbc:sqlite:" + root.resolve(path),
            config.toProperties(),
        )
        .use(body)
  }

  @OptIn(kotlin.io.path.ExperimentalPathApi::class)
  override fun close() {
    hosts.forEach { runCatching { it.invalidateAll(Invalidation.RUN_ENDED) } }
    pools.close()
    runCatching { root.deleteRecursively() }
  }
}
