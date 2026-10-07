package dev.lawlan.runline.accessors.jdbc

import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties
import java.util.UUID
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * A real PostgreSQL instance shared by every test in the JVM, of the version the Engine runs on
 * (the image tag matches `.devcontainer/docker-compose.yml`). Nothing here stands in for it.
 */
object RealPostgres {
  private val container: PostgreSQLContainer by lazy {
    PostgreSQLContainer("postgres:17-alpine").apply {
      withStartupTimeout(java.time.Duration.ofMinutes(3))
      start()
    }
  }

  val host: String
    get() = container.host

  val port: Int
    get() = container.getMappedPort(5432)

  private val adminUser: String
    get() = container.username

  private val adminPassword: String
    get() = container.password

  private fun connect(database: String, user: String, password: String): Connection =
      DriverManager.getConnection(
          "jdbc:postgresql://$host:$port/$database",
          Properties().apply {
            setProperty("user", user)
            setProperty("password", password)
            setProperty("ApplicationName", "test-observer")
            setProperty("loginTimeout", "30")
          },
      )

  /** A brand new, empty database on the shared instance, so a test starts from a known state. */
  fun newDatabase(): TestDatabase {
    val name = "t_" + UUID.randomUUID().toString().replace("-", "")
    connect(container.databaseName, adminUser, adminPassword).use {
      it.createStatement().use { s -> s.execute("CREATE DATABASE $name") }
    }
    return TestDatabase(name)
  }

  /** One database of [RealPostgres]: what a test does to it and sees of it is its own business. */
  class TestDatabase(val name: String) : AutoCloseable {
    private val opened = mutableListOf<Connection>()

    /** A connection of the test itself, as the administrator, for what it sets up and observes. */
    fun admin(): Connection =
        connect(name, adminUser, adminPassword).also { synchronized(opened) { opened += it } }

    /** Runs [sql] as the administrator. */
    fun execute(sql: String) {
      admin().use { c -> c.createStatement().use { it.execute(sql) } }
    }

    /** The first column of the first row of [sql], as the administrator, as text. */
    fun scalar(sql: String): String? =
        admin().use { c ->
          c.createStatement().use { s ->
            s.executeQuery(sql).use { if (it.next()) it.getString(1) else null }
          }
        }

    /** A role that may log in with [password]; [grants] are statements run as the administrator. */
    fun createRole(role: String, password: String, vararg grants: String) {
      execute("CREATE ROLE $role LOGIN PASSWORD '$password'")
      execute("GRANT CONNECT ON DATABASE $name TO $role")
      grants.forEach(::execute)
    }

    /** The sessions on this database, by the application name each connected with. */
    fun sessions(applicationName: String? = null, user: String? = null): Int =
        scalar(
                "SELECT count(*) FROM pg_stat_activity WHERE datname = '$name' " +
                    "AND pid <> pg_backend_pid() AND application_name <> 'test-observer'" +
                    (applicationName?.let { " AND application_name = '$it'" } ?: "") +
                    (user?.let { " AND usename = '$it'" } ?: "")
            )!!
            .toInt()

    /** The sessions that are running a statement now, not idle. */
    fun active(applicationName: String): Int =
        scalar(
                "SELECT count(*) FROM pg_stat_activity WHERE datname = '$name' " +
                    "AND application_name = '$applicationName' AND state = 'active'"
            )!!
            .toInt()

    /** The sessions that have a transaction open and are doing nothing in it. */
    fun idleInTransaction(applicationName: String): Int =
        scalar(
                "SELECT count(*) FROM pg_stat_activity WHERE datname = '$name' " +
                    "AND application_name = '$applicationName' AND state LIKE 'idle in transaction%'"
            )!!
            .toInt()

    override fun close() {
      synchronized(opened) { opened.forEach { runCatching { it.close() } } }
    }
  }
}
