package dev.lawlan.runline.engine.db

import dev.lawlan.runline.engine.support.PostgresTestContainer
import java.sql.SQLException
import kotlin.test.*
import org.flywaydb.core.Flyway

/**
 * The migration that gives shared resources a type (WI-40, ADR-019), run on a real PostgreSQL that
 * already holds resources defined before it existed.
 */
class ResourceTypeMigrationTest {
  private val database = PostgresTestContainer.newDatabase()

  /** The schema as it was before resources had a type: the migrations up to V6. */
  private fun migrateToBeforeTypes() {
    Flyway.configure()
        .dataSource(database.url, database.user, database.password)
        .locations("classpath:db/migration")
        .target("6")
        .load()
        .migrate()
  }

  private fun <T> query(sql: String, read: (java.sql.ResultSet) -> T): T =
      PostgresTestContainer.connect(database).use { c ->
        c.createStatement().use { s -> s.executeQuery(sql).use(read) }
      }

  private fun execute(sql: String) =
      PostgresTestContainer.connect(database).use { c ->
        c.createStatement().use { it.execute(sql) }
      }

  private data class Row(
      val name: String,
      val capacity: Int,
      val enabled: Boolean,
      val createdBy: String,
      val updatedBy: String,
      val type: String,
      val settings: String,
      val secretAlias: String?,
  )

  private fun rows(): List<Row> =
      query(
          "SELECT name, capacity, enabled, created_by, updated_by, type, settings::text, " +
              "secret_alias FROM shared_resource ORDER BY name"
      ) { rs ->
        generateSequence {
          if (rs.next())
              Row(
                  rs.getString(1),
                  rs.getInt(2),
                  rs.getBoolean(3),
                  rs.getString(4),
                  rs.getString(5),
                  rs.getString(6),
                  rs.getString(7),
                  rs.getString(8),
              )
          else null
        }
            .toList()
      }

  private fun legacyResources() {
    execute(
        "INSERT INTO shared_resource (name, capacity, enabled, created_by, created_at, " +
            "updated_by, updated_at) VALUES " +
            "('lemonade', 3, TRUE, 'root', now(), 'ops', now()), " +
            "('gpu', 1, FALSE, 'alice-admin', now(), 'alice-admin', now())"
    )
  }

  @Test
  fun `resources defined before the migration become counters and keep everything else`() {
    migrateToBeforeTypes()
    legacyResources()

    val applied = DatabaseMigrator(database).migrate()

    assertTrue(applied > 0)
    assertEquals(
        listOf(
            Row("gpu", 1, false, "alice-admin", "alice-admin", "counter", "{}", null),
            Row("lemonade", 3, true, "root", "ops", "counter", "{}", null),
        ),
        rows(),
    )
  }

  @Test
  fun `running the migration again changes nothing`() {
    migrateToBeforeTypes()
    legacyResources()
    DatabaseMigrator(database).migrate()
    val before = rows()

    assertEquals(0, DatabaseMigrator(database).migrate())

    assertEquals(before, rows())
  }

  @Test
  fun `the database accepts only the four types of the closed set`() {
    DatabaseMigrator(database).migrate()
    fun insert(type: String) =
        execute(
            "INSERT INTO shared_resource (name, type, settings, capacity, enabled, created_by, " +
                "created_at, updated_by, updated_at) VALUES ('r-$type', '$type', '{}', 1, TRUE, " +
                "'root', now(), 'root', now())"
        )

    listOf("counter", "file", "jdbc-pool", "openai-compatible").forEach { insert(it) }

    assertFailsWith<SQLException> { insert("http-endpoint") }
    assertEquals(4, rows().size)
  }
}
