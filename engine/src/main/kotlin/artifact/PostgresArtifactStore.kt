package dev.lawlan.runline.engine.artifact

import dev.lawlan.runline.analyzer.Verdict
import dev.lawlan.runline.engine.db.AllowListLock
import java.nio.file.Files
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.OffsetDateTime
import javax.sql.DataSource
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PostgresArtifactStore(private val dataSource: DataSource) : ArtifactStore {
  private val json = Json { encodeDefaults = true }

  override fun saveIfAbsent(artifact: NewArtifact): SaveResult = transaction { connection ->
    artifact.requiredAllowListVersion?.let {
      // Shared: stores run side by side; a change of the list takes the lock exclusively and so
      // waits for stores in progress, and a store that comes after it sees the new version.
      AllowListLock.lockShared(connection)
      if (!isCurrentAllowListVersion(connection, it)) return@transaction SaveResult.AllowListChanged
    }
    val id = insertArtifact(connection, artifact)
    if (id == null) {
      SaveResult.AlreadyExists(checkNotNull(find(connection, artifact.contentHash)))
    } else {
      artifact.definitions.forEach { insertDefinition(connection, id, it) }
      SaveResult.Created(checkNotNull(find(connection, artifact.contentHash)))
    }
  }

  override fun findByHash(contentHash: String): ArtifactRecord? =
      dataSource.connection.use { find(it, contentHash) }

  override fun list(visibility: Visibility): List<ArtifactRecord> =
      dataSource.connection.use { connection ->
        val owner = (visibility as? Visibility.OwnedBy)?.uploader
        connection
            .prepareStatement(
                "SELECT id, content_hash, size_bytes, uploaded_by, uploaded_at " +
                    "FROM pipeline_artifact WHERE (?::text IS NULL OR uploaded_by = ?) " +
                    "ORDER BY id"
            )
            .use { statement ->
              statement.setString(1, owner)
              statement.setString(2, owner)
              statement.executeQuery().use { rs ->
                generateSequence { if (rs.next()) rs.artifactRow() else null }.toList()
              }
            }
            .map { it.toRecord(definitionsOf(connection, it.id)) }
      }

  override fun delete(contentHash: String): DeleteResult = transaction { connection ->
    try {
      connection.prepareStatement("DELETE FROM pipeline_artifact WHERE content_hash = ?").use {
        it.setString(1, contentHash)
        if (it.executeUpdate() == 0) DeleteResult.NotFound else DeleteResult.Deleted
      }
    } catch (e: SQLException) {
      if (e.sqlState == FOREIGN_KEY_VIOLATION) DeleteResult.InUse else throw e
    }
  }

  /** Runs [block] in one transaction; rolls back on any failure. */
  private fun <T> transaction(block: (Connection) -> T): T =
      dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
          block(connection).also { connection.commit() }
        } catch (e: Throwable) {
          connection.rollback()
          throw e
        }
      }

  /** True when [version] is the newest allow list version, or when no managed list exists yet. */
  private fun isCurrentAllowListVersion(connection: Connection, version: String): Boolean =
      connection.prepareStatement("SELECT MAX(version) FROM allowlist_version").use {
        it.executeQuery().use { rs ->
          rs.next()
          val current = rs.getLong(1)
          rs.wasNull() || current.toString() == version
        }
      }

  /** Inserts the artifact row; returns its id, or null when the content hash already exists. */
  private fun insertArtifact(connection: Connection, artifact: NewArtifact): Long? =
      Files.newInputStream(artifact.content).use { content ->
        connection
            .prepareStatement(
                "INSERT INTO pipeline_artifact " +
                    "(content_hash, content, size_bytes, uploaded_by, uploaded_at) " +
                    "VALUES (?, ?, ?, ?, ?) ON CONFLICT (content_hash) DO NOTHING RETURNING id"
            )
            .use {
              it.setString(1, artifact.contentHash)
              it.setBinaryStream(2, content, artifact.sizeBytes)
              it.setLong(3, artifact.sizeBytes)
              it.setString(4, artifact.uploadedBy)
              it.setObject(5, artifact.uploadedAt.atOffset(java.time.ZoneOffset.UTC))
              it.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
            }
      }

  private fun insertDefinition(connection: Connection, artifactId: Long, d: NewDefinition) {
    connection
        .prepareStatement(
            "INSERT INTO pipeline_definition " +
                "(artifact_id, class_name, name, metadata, verdict, reasons, allow_list_version) " +
                "VALUES (?, ?, ?, ?::jsonb, ?, ?::jsonb, ?)"
        )
        .use {
          it.setLong(1, artifactId)
          it.setString(2, d.className)
          it.setString(3, d.name)
          it.setString(4, json.encodeToString(d.metadata))
          it.setString(5, d.verdict.name)
          it.setString(6, json.encodeToString(d.reasons))
          it.setString(7, d.allowListVersion)
          it.executeUpdate()
        }
  }

  private fun find(connection: Connection, contentHash: String): ArtifactRecord? =
      connection
          .prepareStatement(
              "SELECT id, content_hash, size_bytes, uploaded_by, uploaded_at " +
                  "FROM pipeline_artifact WHERE content_hash = ?"
          )
          .use {
            it.setString(1, contentHash)
            it.executeQuery().use { rs -> if (rs.next()) rs.artifactRow() else null }
          }
          ?.let { it.toRecord(definitionsOf(connection, it.id)) }

  private fun definitionsOf(connection: Connection, artifactId: Long): List<DefinitionRecord> =
      connection
          .prepareStatement(
              "SELECT class_name, name, metadata, verdict, reasons, allow_list_version, " +
                  "allow_unsafe_execution, unsafe_setting_set_by " +
                  "FROM pipeline_definition WHERE artifact_id = ? ORDER BY id"
          )
          .use {
            it.setLong(1, artifactId)
            it.executeQuery().use { rs ->
              generateSequence {
                if (rs.next()) {
                  DefinitionRecord(
                      className = rs.getString("class_name"),
                      name = rs.getString("name"),
                      metadata = json.decodeFromString(rs.getString("metadata")),
                      verdict = Verdict.valueOf(rs.getString("verdict")),
                      reasons = json.decodeFromString(rs.getString("reasons")),
                      allowListVersion = rs.getString("allow_list_version"),
                      allowUnsafeExecution = rs.getBoolean("allow_unsafe_execution"),
                      unsafeSettingSetBy = rs.getString("unsafe_setting_set_by"),
                  )
                } else null
              }
                  .toList()
            }
          }

  private class ArtifactRow(
      val id: Long,
      val contentHash: String,
      val sizeBytes: Long,
      val uploadedBy: String,
      val uploadedAt: java.time.Instant,
  ) {
    fun toRecord(definitions: List<DefinitionRecord>) =
        ArtifactRecord(contentHash, sizeBytes, uploadedBy, uploadedAt, definitions)
  }

  private fun ResultSet.artifactRow() =
      ArtifactRow(
          getLong("id"),
          getString("content_hash"),
          getLong("size_bytes"),
          getString("uploaded_by"),
          getObject("uploaded_at", OffsetDateTime::class.java).toInstant(),
      )

  private companion object {
    const val FOREIGN_KEY_VIOLATION = "23503"
  }
}
