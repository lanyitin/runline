package dev.lawlan.runline.accessors.jdbc

import dev.lawlan.runline.accessors.BoundResources
import dev.lawlan.runline.accessors.ResourceObserver
import dev.lawlan.runline.core.ResourceFailure
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** A statement of a run that the host refused or that failed: only what a run is told. */
class Failed(val failure: ResourceFailure, val answer: Map<String, Any?>) :
    RuntimeException(failure.name) {
  val sqlState: String? = answer["sqlState"] as String?
  val errorId: String? = answer["errorId"] as String?
}

/**
 * What the tests of `jdbc-pool` accessors stand on: a real PostgreSQL with a database of its own,
 * an account with a password, the pools of the host and a [BoundResources] that a run's calls go
 * through, as the Engine's do. A statement is a call of the host with JDK types, and what comes
 * back is the answer a run gets.
 */
class JdbcRig(
    val profiles: JdbcProfiles = JdbcProfiles(listOf(PostgresProfile)),
    /**
     * The statements that give the account its rights, from its name; all of the schema by default.
     */
    grants: (String) -> List<String> = { listOf("GRANT ALL ON SCHEMA public TO $it") },
) : AutoCloseable {
  val db = RealPostgres.newDatabase()

  /** Roles belong to the whole server, so each rig has a role of its own. */
  val role = "app_" + UUID.randomUUID().toString().replace("-", "").take(12)
  val password = "pw-" + UUID.randomUUID().toString().replace("-", "")
  val pools = JdbcPools(profiles)
  val observer = RecordingObserver()
  private val hosts = mutableListOf<BoundResources>()

  init {
    db.createRole(role, password, *grants(role).toTypedArray())
  }

  /** A host with one `jdbc-pool` resource called [name], as an administrator defined it. */
  fun host(
      extra: String = "",
      capacity: Int = 1,
      name: String = "db",
      user: String = role,
      password: String? = this.password,
      port: Int = RealPostgres.port,
      hostName: String = RealPostgres.host,
      resourceObserver: ResourceObserver = ResourceObserver.NONE,
  ): BoundResources {
    val credential = password?.let { JdbcCredential.Password(it) } ?: JdbcCredential.None
    val binding =
        pools.bind(name, settings(extra, user, port, hostName), credential, capacity, observer)
    return BoundResources(mapOf(name to binding), resourceObserver).also { hosts += it }
  }

  fun settings(
      extra: String = "",
      user: String = role,
      port: Int = RealPostgres.port,
      hostName: String = RealPostgres.host,
  ): JdbcSettings {
    val json =
        """{"kind":"postgresql","host":"$hostName","port":$port,"database":"${db.name}","username":"$user"${if (extra.isEmpty()) "" else ",$extra"}}"""
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

  override fun close() {
    hosts.forEach {
      runCatching { it.invalidateAll(dev.lawlan.runline.accessors.Invalidation.RUN_ENDED) }
    }
    pools.close()
    db.close()
  }
}

/** What the observer of the tests was told, in order. */
class RecordingObserver : JdbcObserver {
  class Finished(
      val resource: String,
      val operation: String,
      val millis: Long,
      val failure: ResourceFailure?,
  )

  val finished = CopyOnWriteArrayList<Finished>()
  val acquireFailures = CopyOnWriteArrayList<ResourceFailure>()

  override fun finished(
      resource: String,
      operation: String,
      millis: Long,
      failure: ResourceFailure?,
  ) {
    finished += Finished(resource, operation, millis, failure)
  }

  override fun acquireFailed(resource: String, failure: ResourceFailure) {
    acquireFailures += failure
  }
}
