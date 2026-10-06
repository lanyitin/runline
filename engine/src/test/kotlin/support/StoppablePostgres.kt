package dev.lawlan.runline.engine.support

import com.github.dockerjava.api.model.ExposedPort
import com.github.dockerjava.api.model.PortBinding
import com.github.dockerjava.api.model.Ports
import dev.lawlan.runline.engine.config.DatabaseConfig
import dev.lawlan.runline.engine.db.DatabaseMigrator
import java.net.ServerSocket
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * A real PostgreSQL container of a test's own, which the test can stop and start again: the shared
 * instance of [PostgresTestContainer] serves every other test and must stay up. The container keeps
 * its data and its address across a stop and a start (the host port is fixed beforehand), so an
 * Engine that was given [database] finds the database where it was when it comes back.
 */
class StoppablePostgres private constructor(private val container: PostgreSQLContainer) :
    AutoCloseable {
  val database: DatabaseConfig =
      DatabaseConfig(container.jdbcUrl, container.username, container.password)

  /** Stops the database, as a failure of the host or the service would. */
  fun stop() {
    container.dockerClient.stopContainerCmd(container.containerId).exec()
  }

  /** Starts it again after [stop]; it needs a moment before it accepts connections. */
  fun start() {
    container.dockerClient.startContainerCmd(container.containerId).exec()
  }

  override fun close() = container.stop()

  companion object {
    /** A started container whose database is migrated, the state an Engine starts from. */
    fun startMigrated(): StoppablePostgres {
      val hostPort = ServerSocket(0).use { it.localPort }
      val container =
          PostgreSQLContainer("postgres:17-alpine").apply {
            withStartupTimeout(TestTimeouts.container)
            withCreateContainerCmdModifier { command ->
              command.hostConfig!!.withPortBindings(
                  PortBinding(Ports.Binding.bindPort(hostPort), ExposedPort(5432))
              )
            }
            start()
          }
      return StoppablePostgres(container).also { DatabaseMigrator(it.database).migrate() }
    }
  }
}
