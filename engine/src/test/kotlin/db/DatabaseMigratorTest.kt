package dev.lawlan.runline.engine.db

import dev.lawlan.runline.engine.support.PostgresTestContainer
import kotlin.test.*

class DatabaseMigratorTest {
  private val database = PostgresTestContainer.newDatabase()
  private val migrator = DatabaseMigrator(database)

  private fun tables(): Set<String> =
      PostgresTestContainer.connect(database).use { c ->
        c.createStatement().use { s ->
          s.executeQuery(
                  "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'"
              )
              .use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toSet() }
        }
      }

  @Test
  fun `migrate creates the artifact and definition tables`() {
    val applied = migrator.migrate()

    assertTrue(applied > 0)
    assertTrue(
        tables().containsAll(setOf("pipeline_artifact", "pipeline_definition")),
        "${tables()}",
    )
  }

  @Test
  fun `migrate creates the shared resource table`() {
    migrator.migrate()

    assertTrue("shared_resource" in tables(), "${tables()}")
  }

  @Test
  fun `migrate is repeatable and applies nothing the second time`() {
    migrator.migrate()
    assertEquals(0, migrator.migrate())
  }

  @Test
  fun `an unmigrated database is reported as out of date`() {
    assertFailsWith<SchemaOutOfDateException> { migrator.requireUpToDate() }
    assertFalse("pipeline_artifact" in tables(), "checking must not migrate")
  }

  @Test
  fun `a migrated database passes the startup check`() {
    migrator.migrate()
    migrator.requireUpToDate()
  }
}
