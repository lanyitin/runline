package dev.lawlan.runline.engine.db

import dev.lawlan.runline.engine.config.ConfigurationException
import dev.lawlan.runline.engine.support.PostgresTestContainer
import io.ktor.server.config.*
import kotlin.test.*

/** The one-off migration process uses the same configuration keys as the Engine itself. */
class MigrateTest {
  @Test
  fun `migrates the database located by postgres settings only`() {
    val database = PostgresTestContainer.newDatabase()
    val config =
        MapApplicationConfig(
            "postgres.url" to database.url,
            "postgres.user" to database.user,
            "postgres.password" to database.password,
        )

    assertTrue(runMigration(config) > 0)
    DatabaseMigrator(database).requireUpToDate()
    assertEquals(0, runMigration(config))
  }

  @Test
  fun `does not need tokens or other Engine settings`() {
    val database = PostgresTestContainer.newDatabase()
    runMigration(
        MapApplicationConfig(
            "postgres.url" to database.url,
            "postgres.user" to database.user,
            "postgres.password" to database.password,
        )
    )
  }

  @Test
  fun `fails fast when the database is not configured`() {
    assertFailsWith<ConfigurationException> { runMigration(MapApplicationConfig()) }
  }
}
