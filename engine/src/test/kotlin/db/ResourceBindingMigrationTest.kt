package dev.lawlan.runline.engine.db

import dev.lawlan.runline.engine.resource.PostgresResourceBindingStore
import dev.lawlan.runline.engine.resource.PostgresResourceStore
import dev.lawlan.runline.engine.resource.ResourceAvailability
import dev.lawlan.runline.engine.resource.ResourceProblem
import dev.lawlan.runline.engine.resource.ResourceProblemKind
import dev.lawlan.runline.engine.support.PostgresTestContainer
import kotlin.test.*
import org.flywaydb.core.Flyway

/**
 * The migration that adds the bindings of resource roles (WI-1012, ADR-025), run on a real
 * PostgreSQL that already holds resources and pipelines declared by name. Nothing existing changes
 * and needs no binding: a role without one gets the resource of its own name, as it always did.
 */
class ResourceBindingMigrationTest {
  private val database = PostgresTestContainer.newDatabase()

  private fun migrateTo(version: String) {
    Flyway.configure()
        .dataSource(database.url, database.user, database.password)
        .locations("classpath:db/migration")
        .target(version)
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

  private fun resources() =
      query(
          "SELECT name, capacity, enabled, type, created_by FROM shared_resource ORDER BY name"
      ) { rs ->
        generateSequence {
          if (rs.next())
              listOf(
                  rs.getString(1),
                  rs.getInt(2),
                  rs.getBoolean(3),
                  rs.getString(4),
                  rs.getString(5),
              )
          else null
        }
            .toList()
      }

  private fun legacyData() {
    execute(
        "INSERT INTO shared_resource (name, capacity, enabled, created_by, created_at, " +
            "updated_by, updated_at, type, settings) VALUES " +
            "('lemonade', 3, TRUE, 'root', now(), 'root', now(), 'counter', '{}'::jsonb), " +
            "('gpu', 1, FALSE, 'root', now(), 'root', now(), 'counter', '{}'::jsonb)"
    )
    execute(
        "INSERT INTO pipeline_artifact (content_hash, content, size_bytes, uploaded_by, uploaded_at) " +
            "VALUES (repeat('a', 64), 'x'::bytea, 1, 'alice', now())"
    )
    execute(
        "INSERT INTO pipeline_definition (artifact_id, class_name, name, metadata, verdict, reasons, " +
            "allow_list_version) SELECT id, 'p.Main', 'main', " +
            "'{\"parameters\":[],\"files\":[],\"network\":{\"unrestricted\":false}," +
            "\"processes\":{\"unrestricted\":false},\"resources\":[\"lemonade\",\"ghost\"]," +
            "\"resourceTypes\":{\"lemonade\":\"counter\"}}'::jsonb, 'SAFE', '[]'::jsonb, '1' " +
            "FROM pipeline_artifact"
    )
  }

  @Test
  fun `the migration adds an empty table of bindings and changes nothing that exists`() {
    migrateTo("8")
    legacyData()
    val before = resources()

    migrateTo("10")

    assertEquals(before, resources())
    assertEquals(
        0,
        query("SELECT count(*) FROM resource_binding") {
          it.next()
          it.getInt(1)
        },
    )
    assertEquals(
        1,
        query("SELECT count(*) FROM pipeline_definition") {
          it.next()
          it.getInt(1)
        },
    )
  }

  @Test
  fun `a pipeline declared by name before the migration is resolved to the same names after it`() {
    migrateTo("8")
    legacyData()
    migrateTo("10")
    val dataSource = dataSourceOf(database)
    val availability =
        ResourceAvailability(
            PostgresResourceStore(dataSource),
            PostgresResourceBindingStore(dataSource),
        )

    val problems =
        availability.problems(
            dev.lawlan.runline.engine.resource.PipelineKey("alice", "main"),
            listOf("lemonade", "ghost"),
            mapOf("lemonade" to "counter"),
        )

    assertEquals(listOf(ResourceProblem("ghost", ResourceProblemKind.UNBOUND)), problems)
  }

  @Test
  fun `a binding needs a pipeline and a role, and a role of an uploader's pipeline has one binding`() {
    migrateTo("10")

    execute("INSERT INTO resource_binding VALUES ('alice', 'p', 'r', 'x', 'alice', now())")

    assertFailsWith<java.sql.SQLException> {
      execute("INSERT INTO resource_binding VALUES ('alice', 'p', 'r', 'y', 'bob', now())")
    }
    assertFailsWith<java.sql.SQLException> {
      execute(
          "INSERT INTO resource_binding (uploader, pipeline, role, set_by, set_at) VALUES ('alice', 'p', 'q', 'a', now())"
      )
    }
  }
}
