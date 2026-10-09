package dev.lawlan.runline.engine.support

import dev.lawlan.runline.engine.config.DatabaseConfig
import java.net.SocketTimeoutException
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Duration
import java.util.Properties
import java.util.UUID
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * A real PostgreSQL instance shared by every test in the JVM. The image tag matches
 * `.devcontainer/docker-compose.yml` to keep dev/test/prod parity.
 */
object PostgresTestContainer {
  private val container: PostgreSQLContainer by lazy {
    PostgreSQLContainer("postgres:17-alpine").apply {
      withStartupTimeout(TestTimeouts.container)
      start()
    }
  }

  val jdbcUrl: String
    get() = container.jdbcUrl

  val username: String
    get() = container.username

  val password: String
    get() = container.password

  /**
   * A connection to [url] for a test's own statements. Connecting, and every wait for an answer on
   * it, is limited to [timeout]; a database that does not answer fails the test with its URL
   * instead of holding it for ever (a read on a socket is not ended by interrupting the thread).
   */
  fun connect(
      url: String,
      user: String,
      password: String,
      timeout: Duration = TestTimeouts.database,
  ): Connection {
    val seconds = ((timeout.toMillis() + 999) / 1000).toString()
    val properties =
        Properties().apply {
          setProperty("user", user)
          setProperty("password", password)
          setProperty("loginTimeout", seconds)
          setProperty("connectTimeout", seconds)
          setProperty("socketTimeout", seconds)
        }
    try {
      return DriverManager.getConnection(url, properties)
    } catch (e: SQLException) {
      val timedOut =
          generateSequence<Throwable>(e) { it.cause }
              .any { it is SocketTimeoutException || it.message?.contains("timed out") == true }
      if (timedOut) {
        throw AssertionError(
            "no answer from the database at $url within ${timeout.toMillis()} ms",
            e,
        )
      }
      throw e
    }
  }

  fun connect(database: DatabaseConfig): Connection =
      connect(database.url, database.user, database.password)

  /**
   * The whole of [database] as `pg_dump` of the server's own version writes it (schema and data, as
   * SQL text), for a test that searches a database's backup (WI-51).
   */
  fun dump(database: DatabaseConfig): String {
    val name = database.url.substringAfterLast('/').substringBefore('?')
    val result = container.execInContainer("pg_dump", "-U", username, "-d", name)
    check(result.exitCode == 0) { "pg_dump failed (${result.exitCode}): ${result.stderr}" }
    return result.stdout
  }

  /** A brand new, empty database on the shared instance, so a test starts from a known state. */
  fun newDatabase(): DatabaseConfig {
    val name = "t_" + UUID.randomUUID().toString().replace("-", "")
    connect(jdbcUrl, username, password).use {
      it.createStatement().use { s -> s.execute("CREATE DATABASE $name") }
    }
    val url = "jdbc:postgresql://${container.host}:${container.getMappedPort(5432)}/$name"
    return DatabaseConfig(url, username, password)
  }
}
