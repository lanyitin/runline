package dev.lawlan.runline.engine.artifact

import dev.lawlan.runline.analyzer.Verdict
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.sql.DataSource
import kotlinx.serialization.json.Json

class PostgresDefinitionStore(private val dataSource: DataSource) : DefinitionStore {
  private val json = Json { ignoreUnknownKeys = true }

  override fun find(contentHash: String, pipelineName: String): StoredDefinition? =
      dataSource.connection.use { connection ->
        connection.prepareStatement("$SELECT WHERE a.content_hash = ? AND d.name = ?").use {
          it.setString(1, contentHash)
          it.setString(2, pipelineName)
          it.executeQuery().use { rs -> if (rs.next()) rs.definition() else null }
        }
      }

  override fun setUnsafeExecution(
      contentHash: String,
      pipelineName: String,
      allow: Boolean,
      by: String,
      at: Instant,
  ): StoredDefinition? =
      dataSource.connection.use { connection ->
        val changed =
            connection
                .prepareStatement(
                    "UPDATE pipeline_definition SET allow_unsafe_execution = ?, " +
                        "unsafe_setting_set_by = ?, unsafe_setting_set_at = ? " +
                        "WHERE name = ? AND artifact_id = " +
                        "(SELECT id FROM pipeline_artifact WHERE content_hash = ?)"
                )
                .use {
                  it.setBoolean(1, allow)
                  it.setString(2, by)
                  it.setObject(3, at.atOffset(ZoneOffset.UTC))
                  it.setString(4, pipelineName)
                  it.setString(5, contentHash)
                  it.executeUpdate()
                }
        if (changed == 0) null else find(contentHash, pipelineName)
      }

  override fun copyContent(contentHash: String, target: Path): Boolean =
      dataSource.connection.use { connection ->
        connection
            .prepareStatement("SELECT content FROM pipeline_artifact WHERE content_hash = ?")
            .use {
              it.setString(1, contentHash)
              it.executeQuery().use { rs ->
                if (!rs.next()) return@use false
                rs.getBinaryStream(1).use { content ->
                  Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING)
                }
                true
              }
            }
      }

  private fun ResultSet.definition() =
      StoredDefinition(
          id = getLong("id"),
          contentHash = getString("content_hash"),
          uploadedBy = getString("uploaded_by"),
          className = getString("class_name"),
          name = getString("name"),
          metadata = json.decodeFromString(getString("metadata")),
          verdict = Verdict.valueOf(getString("verdict")),
          allowUnsafeExecution = getBoolean("allow_unsafe_execution"),
          unsafeSettingSetBy = getString("unsafe_setting_set_by"),
          unsafeSettingSetAt =
              getObject("unsafe_setting_set_at", OffsetDateTime::class.java)?.toInstant(),
      )

  private companion object {
    const val SELECT =
        "SELECT d.id, a.content_hash, a.uploaded_by, d.class_name, d.name, d.metadata, " +
            "d.verdict, d.allow_unsafe_execution, d.unsafe_setting_set_by, " +
            "d.unsafe_setting_set_at FROM pipeline_definition d " +
            "JOIN pipeline_artifact a ON a.id = d.artifact_id"
  }
}
