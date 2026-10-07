package dev.lawlan.runline.engine.db

import dev.lawlan.runline.engine.support.PostgresTestContainer
import java.security.MessageDigest
import java.sql.SQLException
import kotlin.test.*
import org.flywaydb.core.Flyway

/**
 * The migration that makes a version the pair (content hash, uploader) and keeps the jar once per
 * content (WI-54, ADR-020), run on a real PostgreSQL that already holds artifacts, definitions,
 * triggers and runs from before it existed.
 */
class ArtifactVersionMigrationTest {
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

  private fun sha256(bytes: ByteArray) =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  private val jarA = ByteArray(5000) { (it * 7).toByte() }
  private val jarB = "another jar".toByteArray()

  /** Two artifacts as they were stored before: the bytes in the artifact row itself. */
  private fun legacyData() {
    PostgresTestContainer.connect(database).use { c ->
      for ((bytes, uploader) in listOf(jarA to "alice", jarB to "bob")) {
        c.prepareStatement(
                "INSERT INTO pipeline_artifact (content_hash, content, size_bytes, uploaded_by, " +
                    "uploaded_at) VALUES (?, ?, ?, ?, now())"
            )
            .use {
              it.setString(1, sha256(bytes))
              it.setBytes(2, bytes)
              it.setLong(3, bytes.size.toLong())
              it.setString(4, uploader)
              it.executeUpdate()
            }
      }
    }
    execute(
        "INSERT INTO pipeline_definition (artifact_id, class_name, name, metadata, verdict, " +
            "reasons, allow_list_version, allow_unsafe_execution, unsafe_setting_set_by, " +
            "unsafe_setting_set_at) SELECT id, 'p.Main', 'main', " +
            "'{\"parameters\":[],\"files\":[],\"network\":{\"unrestricted\":false}," +
            "\"processes\":{\"unrestricted\":false},\"resources\":[]}'::jsonb, 'UNSAFE', " +
            "'[]'::jsonb, '3', TRUE, 'root', now() FROM pipeline_artifact WHERE uploaded_by = 'alice'"
    )
    execute(
        "INSERT INTO pipeline_trigger (name, kind, definition_id, parameters, enabled, " +
            "cron_expression, time_zone, created_by, created_at, updated_by, updated_at) " +
            "SELECT 'nightly', 'CRON', id, '{}'::jsonb, TRUE, '0 0 * * *', 'UTC', 'root', now(), " +
            "'root', now() FROM pipeline_definition"
    )
    execute(
        "INSERT INTO run (id, definition_id, state, source_kind, source_name, parameters, " +
            "created_at, unsafe_execution) SELECT gen_random_uuid(), id, 'SUCCEEDED', " +
            "'MANUAL', 'alice', '{}'::jsonb, now(), TRUE FROM pipeline_definition"
    )
  }

  private data class Version(val id: Long, val hash: String, val uploader: String, val size: Long)

  private fun versions(): List<Version> =
      query(
          "SELECT a.id, a.content_hash, a.uploaded_by, c.size_bytes FROM pipeline_artifact a " +
              "JOIN artifact_content c ON c.content_hash = a.content_hash ORDER BY a.id"
      ) { rs ->
        generateSequence {
          if (rs.next()) Version(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4))
          else null
        }
            .toList()
      }

  @Test
  fun `every stored artifact becomes the only version of its content, bytes intact and by hash`() {
    migrateTo("8")
    legacyData()
    val before =
        query("SELECT id, content_hash, uploaded_by FROM pipeline_artifact ORDER BY id") { rs ->
          generateSequence {
            if (rs.next()) Triple(rs.getLong(1), rs.getString(2), rs.getString(3)) else null
          }
              .toList()
        }

    DatabaseMigrator(database).migrate()

    assertEquals(before, versions().map { Triple(it.id, it.hash, it.uploader) })
    assertEquals(listOf(5000L, jarB.size.toLong()), versions().map { it.size })
    for ((bytes, hash) in listOf(jarA to sha256(jarA), jarB to sha256(jarB))) {
      val stored =
          query("SELECT content FROM artifact_content WHERE content_hash = '$hash'") { rs ->
            rs.next()
            rs.getBytes(1)
          }
      assertContentEquals(bytes, stored)
      assertEquals(hash, sha256(stored))
    }
  }

  @Test
  fun `definitions, their judgements and unsafe settings, triggers and runs are untouched`() {
    migrateTo("8")
    legacyData()
    fun snapshot() =
        query(
            "SELECT d.id, d.artifact_id, d.name, d.verdict, d.allow_list_version, " +
                "d.allow_unsafe_execution, d.unsafe_setting_set_by, " +
                "(SELECT count(*) FROM pipeline_trigger t WHERE t.definition_id = d.id), " +
                "(SELECT count(*) FROM run r WHERE r.definition_id = d.id) " +
                "FROM pipeline_definition d ORDER BY d.id"
        ) { rs ->
          generateSequence {
            if (rs.next()) (1..9).map { rs.getObject(it) } else null
          }
              .toList()
        }
    val before = snapshot()
    assertEquals(1, before.size)

    DatabaseMigrator(database).migrate()

    assertEquals(before, snapshot())
  }

  @Test
  fun `a version in use is still not deletable after the migration`() {
    migrateTo("8")
    legacyData()
    DatabaseMigrator(database).migrate()

    val e =
        assertFailsWith<SQLException> {
          execute("DELETE FROM pipeline_artifact WHERE uploaded_by = 'alice'")
        }

    assertEquals("23503", e.sqlState)
    assertEquals(2, versions().size)
  }

  @Test
  fun `after the migration the same content can be a version of each uploader but only one of each`() {
    migrateTo("8")
    legacyData()
    DatabaseMigrator(database).migrate()
    val hash = sha256(jarA)
    fun insert(uploader: String) =
        execute(
            "INSERT INTO pipeline_artifact (content_hash, uploaded_by, uploaded_at) " +
                "VALUES ('$hash', '$uploader', now())"
        )

    insert("bob")

    assertFailsWith<SQLException> { insert("bob") }
    assertFailsWith<SQLException> { insert("alice") }
    assertEquals(3, versions().size)
  }

  @Test
  fun `content that no version refers to cannot be missing for a version`() {
    DatabaseMigrator(database).migrate()

    assertFailsWith<SQLException> {
      execute(
          "INSERT INTO pipeline_artifact (content_hash, uploaded_by, uploaded_at) " +
              "VALUES ('${"a".repeat(64)}', 'alice', now())"
      )
    }
  }

  @Test
  fun `running the migration again changes nothing`() {
    migrateTo("8")
    legacyData()
    DatabaseMigrator(database).migrate()
    val before = versions()

    assertEquals(0, DatabaseMigrator(database).migrate())

    assertEquals(before, versions())
  }
}
