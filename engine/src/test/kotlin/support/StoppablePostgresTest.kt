package dev.lawlan.runline.engine.support

import java.net.ServerSocket
import kotlin.test.*

/** The stoppable database comes back at the address it had, whatever else holds ports meanwhile. */
class StoppablePostgresTest {
  @Test
  fun `the database is reachable again at its address after a stop, even if its old host port was taken`() {
    StoppablePostgres.startMigrated().use { postgres ->
      val port = Regex(":(\\d+)/").find(postgres.database.url)!!.groupValues[1].toInt()
      postgres.stop()
      // Another process takes the port the database had; whoever owns the address must not need it.
      val intruder = runCatching { ServerSocket(port) }.getOrNull()
      try {
        postgres.start()
        awaitCondition("the database to accept connections again") {
          runCatching { PostgresTestContainer.connect(postgres.database).use { true } }
              .getOrDefault(false)
        }
      } finally {
        intruder?.close()
      }
    }
  }
}
