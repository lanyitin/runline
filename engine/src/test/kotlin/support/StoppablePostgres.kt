package dev.lawlan.runline.engine.support

import dev.lawlan.runline.engine.config.DatabaseConfig
import dev.lawlan.runline.engine.db.DatabaseMigrator
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * A real PostgreSQL container of a test's own, which the test can stop and start again: the shared
 * instance of [PostgresTestContainer] serves every other test and must stay up. Docker may give the
 * container another host port when it starts again, so [database] points at a [TcpForwarder] that
 * holds one port for the whole life of the test (taken from the operating system, never reserved
 * and released) and sends each connection to wherever the container is at that moment. An Engine
 * that was given [database] finds the database where it was when it comes back.
 */
class StoppablePostgres
private constructor(
    private val container: PostgreSQLContainer,
    private val forwarder: TcpForwarder,
) : AutoCloseable {
  val database: DatabaseConfig =
      DatabaseConfig(
          "jdbc:postgresql://localhost:${forwarder.port}/${container.databaseName}",
          container.username,
          container.password,
      )

  /** Stops the database, as a failure of the host or the service would. */
  fun stop() {
    container.dockerClient.stopContainerCmd(container.containerId).exec()
  }

  /** Starts it again after [stop]; it needs a moment before it accepts connections. */
  fun start() {
    container.dockerClient.startContainerCmd(container.containerId).exec()
  }

  override fun close() {
    forwarder.close()
    container.stop()
  }

  companion object {
    /** A started container whose database is migrated, the state an Engine starts from. */
    fun startMigrated(): StoppablePostgres {
      val container =
          PostgreSQLContainer("postgres:17-alpine").apply {
            withStartupTimeout(TestTimeouts.container)
            start()
          }
      val forwarder = TcpForwarder(target = { currentAddress(container) }).also { it.start() }
      return StoppablePostgres(container, forwarder).also {
        DatabaseMigrator(it.database).migrate()
      }
    }

    /** Where the container is reachable now; asked again for each connection, not remembered. */
    private fun currentAddress(container: PostgreSQLContainer): Pair<String, Int> {
      val info = container.dockerClient.inspectContainerCmd(container.containerId).exec()
      val binding =
          info.networkSettings.ports.bindings.entries.first { it.key.port == 5432 }.value.first()
      return container.host to binding.hostPortSpec.toInt()
    }
  }
}

/**
 * A real TCP forwarder: it listens on a port the operating system gave it and copies bytes both
 * ways between each client and the address [target] names when the client connects.
 */
class TcpForwarder(private val target: () -> Pair<String, Int>) : AutoCloseable {
  private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
  private val threads = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
  val port: Int = server.localPort

  fun start() {
    threads.execute {
      while (!server.isClosed) {
        val client = runCatching { server.accept() }.getOrNull() ?: break
        threads.execute { serve(client) }
      }
    }
  }

  private fun serve(client: Socket) {
    val upstream = runCatching { target().let { (host, port) -> Socket(host, port) } }.getOrNull()
    if (upstream == null) {
      client.close()
      return
    }
    threads.execute { copy(client, upstream) }
    copy(upstream, client)
  }

  private fun copy(from: Socket, to: Socket) {
    try {
      from.getInputStream().copyTo(to.getOutputStream())
    } catch (_: Exception) {} finally {
      from.close()
      to.close()
    }
  }

  override fun close() {
    server.close()
    threads.shutdownNow()
  }
}
