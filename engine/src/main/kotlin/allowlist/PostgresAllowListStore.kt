package dev.lawlan.runline.engine.allowlist

import dev.lawlan.runline.analyzer.Verdict
import dev.lawlan.runline.engine.db.AllowListLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.sql.DataSource
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PostgresAllowListStore(private val dataSource: DataSource) : AllowListStore {
  override fun current(): AllowListSnapshot? = read { it.snapshot() }

  override fun versions(limit: Int): List<AllowListVersion> = read { session ->
    (session as PostgresSession).versions(limit)
  }

  override fun <T> change(block: (AllowListSession) -> T): T =
      dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
          AllowListLock.lockExclusively(connection)
          block(PostgresSession(connection)).also { connection.commit() }
        } catch (e: Throwable) {
          connection.rollback()
          throw e
        }
      }

  override fun <T> read(block: (AllowListSession) -> T): T =
      dataSource.connection.use { connection ->
        connection.autoCommit = false
        connection.isReadOnly = true
        // One snapshot for every statement, so the version and the entries belong together.
        connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
        try {
          block(PostgresSession(connection))
        } finally {
          connection.rollback()
        }
      }

  private class PostgresSession(private val connection: Connection) : AllowListSession {
    private val json = Json { encodeDefaults = true }

    override fun snapshot(): AllowListSnapshot? {
      val version = versions(1).firstOrNull() ?: return null
      return AllowListSnapshot(version, entries())
    }

    fun versions(limit: Int): List<AllowListVersion> =
        connection
            .prepareStatement(
                "SELECT version, changed_by, changed_at, action, detail, rejudged_definitions, " +
                    "became_unsafe, became_safe FROM allowlist_version ORDER BY version DESC LIMIT ?"
            )
            .use {
              it.setInt(1, limit)
              it.executeQuery().use { rs -> rs.all { rs.version() } }
            }

    private fun entries(): List<StoredEntry> =
        connection
            .prepareStatement(
                "SELECT kind, name, exact_only, created_by, created_at, updated_by, updated_at " +
                    "FROM allowlist_entry ORDER BY id"
            )
            .use { it.executeQuery().use { rs -> rs.all { rs.entry() } } }

    override fun nextVersion(): Long =
        connection
            .prepareStatement("SELECT COALESCE(MAX(version), 0) + 1 FROM allowlist_version")
            .use {
              it.executeQuery().use { rs ->
                rs.next()
                rs.getLong(1)
              }
            }

    override fun insertEntry(
        kind: EntryKind,
        name: String,
        exactOnly: Boolean,
        by: String,
        at: Instant,
    ) {
      connection
          .prepareStatement(
              "INSERT INTO allowlist_entry (kind, name, exact_only, created_by, created_at, " +
                  "updated_by, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)"
          )
          .use {
            it.setString(1, kind.name)
            it.setString(2, name)
            it.setBoolean(3, exactOnly)
            it.setString(4, by)
            it.setObject(5, at.atOffset(ZoneOffset.UTC))
            it.setString(6, by)
            it.setObject(7, at.atOffset(ZoneOffset.UTC))
            it.executeUpdate()
          }
    }

    override fun updateEntry(
        kind: EntryKind,
        name: String,
        newName: String,
        exactOnly: Boolean,
        by: String,
        at: Instant,
    ): Boolean =
        connection
            .prepareStatement(
                "UPDATE allowlist_entry SET name = ?, exact_only = ?, updated_by = ?, " +
                    "updated_at = ? WHERE kind = ? AND name = ?"
            )
            .use {
              it.setString(1, newName)
              it.setBoolean(2, exactOnly)
              it.setString(3, by)
              it.setObject(4, at.atOffset(ZoneOffset.UTC))
              it.setString(5, kind.name)
              it.setString(6, name)
              it.executeUpdate() == 1
            }

    override fun deleteEntry(kind: EntryKind, name: String): Boolean =
        connection.prepareStatement("DELETE FROM allowlist_entry WHERE kind = ? AND name = ?").use {
          it.setString(1, kind.name)
          it.setString(2, name)
          it.executeUpdate() == 1
        }

    override fun appendVersion(version: AllowListVersion) {
      connection
          .prepareStatement(
              "INSERT INTO allowlist_version (version, changed_by, changed_at, action, detail, " +
                  "rejudged_definitions, became_unsafe, became_safe) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
          )
          .use {
            it.setLong(1, version.version)
            it.setString(2, version.changedBy)
            it.setObject(3, version.changedAt.atOffset(ZoneOffset.UTC))
            it.setString(4, version.action.name)
            it.setString(5, version.detail)
            it.setInt(6, version.rejudgedDefinitions)
            it.setInt(7, version.becameUnsafe)
            it.setInt(8, version.becameSafe)
            it.executeUpdate()
          }
    }

    override fun artifacts(): List<ArtifactRef> =
        connection
            .prepareStatement("SELECT id, content_hash FROM pipeline_artifact ORDER BY id")
            .use {
              it.executeQuery().use { rs ->
                rs.all { ArtifactRef(rs.getLong("id"), rs.getString("content_hash")) }
              }
            }

    override fun copyArtifact(artifactId: Long, target: Path) {
      connection
          .prepareStatement(
              "SELECT c.content FROM pipeline_artifact a " +
                  "JOIN artifact_content c USING (content_hash) WHERE a.id = ?"
          )
          .use {
            it.setLong(1, artifactId)
            it.executeQuery().use { rs ->
              check(rs.next()) { "Artifact $artifactId does not exist" }
              rs.getBinaryStream(1).use { content ->
                Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING)
              }
            }
          }
    }

    override fun definitionsOf(artifactId: Long): List<JudgedDefinition> =
        connection
            .prepareStatement(
                "SELECT id, class_name, name, verdict, allow_unsafe_execution " +
                    "FROM pipeline_definition WHERE artifact_id = ? ORDER BY id"
            )
            .use {
              it.setLong(1, artifactId)
              it.executeQuery().use { rs ->
                rs.all {
                  JudgedDefinition(
                      rs.getLong("id"),
                      rs.getString("class_name"),
                      rs.getString("name"),
                      Verdict.valueOf(rs.getString("verdict")),
                      rs.getBoolean("allow_unsafe_execution"),
                  )
                }
              }
            }

    override fun rejudge(definitionId: Long, judgement: NewJudgement) {
      connection
          .prepareStatement(
              "UPDATE pipeline_definition SET verdict = ?, reasons = ?::jsonb, " +
                  "allow_list_version = ?, " +
                  "allow_unsafe_execution = allow_unsafe_execution AND NOT ?, " +
                  "unsafe_setting_set_by = CASE WHEN ? AND allow_unsafe_execution THEN ? " +
                  "ELSE unsafe_setting_set_by END, " +
                  "unsafe_setting_set_at = CASE WHEN ? AND allow_unsafe_execution THEN ?::timestamptz " +
                  "ELSE unsafe_setting_set_at END WHERE id = ?"
          )
          .use {
            val at = judgement.at.atOffset(ZoneOffset.UTC).toString()
            it.setString(1, judgement.verdict.name)
            it.setString(2, json.encodeToString(judgement.reasons))
            it.setString(3, judgement.allowListVersion)
            it.setBoolean(4, judgement.revokeUnsafeExecution)
            it.setBoolean(5, judgement.revokeUnsafeExecution)
            it.setString(6, judgement.by)
            it.setBoolean(7, judgement.revokeUnsafeExecution)
            it.setString(8, at)
            it.setLong(9, definitionId)
            it.executeUpdate()
          }
    }

    private fun ResultSet.version() =
        AllowListVersion(
            version = getLong("version"),
            changedBy = getString("changed_by"),
            changedAt = getObject("changed_at", OffsetDateTime::class.java).toInstant(),
            action = VersionAction.valueOf(getString("action")),
            detail = getString("detail"),
            rejudgedDefinitions = getInt("rejudged_definitions"),
            becameUnsafe = getInt("became_unsafe"),
            becameSafe = getInt("became_safe"),
        )

    private fun ResultSet.entry() =
        StoredEntry(
            kind = EntryKind.valueOf(getString("kind")),
            name = getString("name"),
            exactOnly = getBoolean("exact_only"),
            createdBy = getString("created_by"),
            createdAt = getObject("created_at", OffsetDateTime::class.java).toInstant(),
            updatedBy = getString("updated_by"),
            updatedAt = getObject("updated_at", OffsetDateTime::class.java).toInstant(),
        )

    private fun <T> ResultSet.all(row: () -> T): List<T> = buildList { while (next()) add(row()) }
  }
}
