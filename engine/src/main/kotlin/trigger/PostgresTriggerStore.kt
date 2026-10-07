package dev.lawlan.runline.engine.trigger

import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class PostgresTriggerStore(private val dataSource: DataSource) : TriggerStore {
  private val parameters = MapSerializer(String.serializer(), String.serializer())

  override fun insert(trigger: NewTrigger): Trigger? {
    val inserted =
        dataSource.connection.use { connection ->
          connection
              .prepareStatement(
                  "INSERT INTO pipeline_trigger (name, kind, definition_id, parameters, enabled, " +
                      "cron_expression, time_zone, secret_hash, secret_rotated_at, created_by, " +
                      "created_at, updated_by, updated_at) " +
                      "VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                      "ON CONFLICT (name) DO NOTHING"
              )
              .use {
                it.setString(1, trigger.name)
                it.setString(2, trigger.kind.name)
                it.setLong(3, trigger.definitionId)
                it.setString(4, Json.encodeToString(parameters, trigger.parameters))
                it.setBoolean(5, trigger.enabled)
                it.setString(6, trigger.cronExpression)
                it.setString(7, trigger.timeZone)
                it.setString(8, trigger.secretHash)
                it.setObject(9, trigger.secretHash?.let { trigger.at.utc() })
                it.setString(10, trigger.by)
                it.setObject(11, trigger.at.utc())
                it.setString(12, trigger.by)
                it.setObject(13, trigger.at.utc())
                it.executeUpdate() == 1
              }
        }
    return if (inserted) find(trigger.name) else null
  }

  override fun find(name: String): Trigger? =
      query("$SELECT WHERE t.name = ?") { it.setString(1, name) }.singleOrNull()

  override fun list(): List<Trigger> = query("$SELECT ORDER BY t.name") {}

  override fun enabledCron(): List<Trigger> =
      query("$SELECT WHERE t.kind = 'CRON' AND t.enabled ORDER BY t.name") {}

  override fun update(name: String, change: TriggerChange, by: String, at: Instant): Trigger? {
    val updated =
        dataSource.connection.use { connection ->
          connection
              .prepareStatement(
                  "UPDATE pipeline_trigger SET definition_id = ?, parameters = ?::jsonb, " +
                      "enabled = ?, cron_expression = ?, time_zone = ?, updated_by = ?, " +
                      "updated_at = ? WHERE name = ?"
              )
              .use {
                it.setLong(1, change.definitionId)
                it.setString(2, Json.encodeToString(parameters, change.parameters))
                it.setBoolean(3, change.enabled)
                it.setString(4, change.cronExpression)
                it.setString(5, change.timeZone)
                it.setString(6, by)
                it.setObject(7, at.utc())
                it.setString(8, name)
                it.executeUpdate() == 1
              }
        }
    return if (updated) find(name) else null
  }

  override fun replaceSecret(name: String, secretHash: String, by: String, at: Instant): Trigger? {
    val updated =
        dataSource.connection.use { connection ->
          connection
              .prepareStatement(
                  "UPDATE pipeline_trigger SET secret_hash = ?, secret_rotated_at = ?, " +
                      "updated_by = ?, updated_at = ? WHERE name = ? AND kind = 'WEBHOOK'"
              )
              .use {
                it.setString(1, secretHash)
                it.setObject(2, at.utc())
                it.setString(3, by)
                it.setObject(4, at.utc())
                it.setString(5, name)
                it.executeUpdate() == 1
              }
        }
    return if (updated) find(name) else null
  }

  override fun delete(name: String): Boolean =
      dataSource.connection.use { connection ->
        connection.prepareStatement("DELETE FROM pipeline_trigger WHERE name = ?").use {
          it.setString(1, name)
          it.executeUpdate() == 1
        }
      }

  override fun webhookCredential(name: String): WebhookCredential? =
      dataSource.connection.use { connection ->
        connection
            .prepareStatement(
                "SELECT id, enabled, secret_hash FROM pipeline_trigger " +
                    "WHERE name = ? AND kind = 'WEBHOOK'"
            )
            .use {
              it.setString(1, name)
              it.executeQuery().use { rows ->
                if (rows.next()) {
                  WebhookCredential(
                      rows.getLong("id"),
                      rows.getBoolean("enabled"),
                      rows.getString("secret_hash"),
                  )
                } else null
              }
            }
      }

  override fun claimDelivery(triggerId: Long, deliveryId: String, at: Instant): Long? =
      claim(
          "INSERT INTO trigger_firing (trigger_id, fired_at, delivery_id, outcome) " +
              "VALUES (?, ?, ?, 'PENDING') ON CONFLICT (trigger_id, delivery_id) DO UPDATE " +
              "SET fired_at = EXCLUDED.fired_at, outcome = 'PENDING', reason = NULL, " +
              "detail = NULL, run_id = NULL WHERE trigger_firing.outcome = 'FAILED' RETURNING id"
      ) {
        it.setLong(1, triggerId)
        it.setObject(2, at.utc())
        it.setString(3, deliveryId)
      }

  override fun claimOccurrence(triggerId: Long, scheduledFor: Instant, at: Instant): Long? =
      claim(
          "INSERT INTO trigger_firing (trigger_id, fired_at, scheduled_for, outcome) " +
              "VALUES (?, ?, ?, 'PENDING') ON CONFLICT (trigger_id, scheduled_for) DO NOTHING " +
              "RETURNING id"
      ) {
        it.setLong(1, triggerId)
        it.setObject(2, at.utc())
        it.setObject(3, scheduledFor.utc())
      }

  override fun settle(
      firingId: Long,
      outcome: FiringOutcome,
      reason: String?,
      detail: String?,
      run: UUID?,
  ) {
    dataSource.connection.use { connection ->
      connection
          .prepareStatement(
              "UPDATE trigger_firing SET outcome = ?, reason = ?, detail = ?, run_id = ? " +
                  "WHERE id = ?"
          )
          .use {
            it.setString(1, outcome.name)
            it.setString(2, reason)
            it.setString(3, detail)
            it.setObject(4, run)
            it.setLong(5, firingId)
            it.executeUpdate()
          }
    }
  }

  override fun firings(triggerId: Long, limit: Int): List<Firing> =
      dataSource.connection.use { connection ->
        connection
            .prepareStatement(
                "SELECT id, fired_at, scheduled_for, delivery_id, outcome, reason, detail, run_id " +
                    "FROM trigger_firing WHERE trigger_id = ? ORDER BY fired_at DESC, id DESC " +
                    "LIMIT ?"
            )
            .use {
              it.setLong(1, triggerId)
              it.setInt(2, limit)
              it.executeQuery().use { rows ->
                buildList {
                  while (rows.next()) {
                    add(
                        Firing(
                            rows.getLong("id"),
                            rows.instant("fired_at"),
                            rows.instantOrNull("scheduled_for"),
                            rows.getString("delivery_id"),
                            FiringOutcome.valueOf(rows.getString("outcome")),
                            rows.getString("reason"),
                            rows.getString("detail"),
                            rows.getObject("run_id", UUID::class.java),
                        )
                    )
                  }
                }
              }
            }
      }

  override fun interruptPending(): Int =
      dataSource.connection.use { connection ->
        connection.createStatement().use {
          it.executeUpdate(
              "UPDATE trigger_firing SET outcome = 'INTERRUPTED', reason = 'engine_stopped' " +
                  "WHERE outcome = 'PENDING'"
          )
        }
      }

  private fun claim(sql: String, bind: (java.sql.PreparedStatement) -> Unit): Long? =
      dataSource.connection.use { connection ->
        connection.prepareStatement(sql).use {
          bind(it)
          it.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
        }
      }

  private fun query(sql: String, bind: (java.sql.PreparedStatement) -> Unit): List<Trigger> =
      dataSource.connection.use { connection: Connection ->
        connection.prepareStatement(sql).use {
          bind(it)
          it.executeQuery().use { rows ->
            buildList {
              while (rows.next()) {
                add(
                    Trigger(
                        id = rows.getLong("id"),
                        name = rows.getString("name"),
                        kind = TriggerKind.valueOf(rows.getString("kind")),
                        definitionId = rows.getLong("definition_id"),
                        contentHash = rows.getString("content_hash"),
                        uploader = rows.getString("uploaded_by"),
                        pipeline = rows.getString("pipeline"),
                        parameters =
                            Json.decodeFromString(parameters, rows.getString("parameters")),
                        enabled = rows.getBoolean("enabled"),
                        cronExpression = rows.getString("cron_expression"),
                        timeZone = rows.getString("time_zone"),
                        secretRotatedAt = rows.instantOrNull("secret_rotated_at"),
                        createdBy = rows.getString("created_by"),
                        createdAt = rows.instant("created_at"),
                        updatedBy = rows.getString("updated_by"),
                        updatedAt = rows.instant("updated_at"),
                    )
                )
              }
            }
          }
        }
      }

  private fun Instant.utc(): OffsetDateTime = atOffset(ZoneOffset.UTC)

  private fun ResultSet.instant(column: String): Instant =
      getObject(column, OffsetDateTime::class.java).toInstant()

  private fun ResultSet.instantOrNull(column: String): Instant? =
      getObject(column, OffsetDateTime::class.java)?.toInstant()

  private companion object {
    // The binding is shown as the version (artifact content hash) and pipeline name.
    const val SELECT =
        "SELECT t.id, t.name, t.kind, t.definition_id, a.content_hash, a.uploaded_by, d.name AS pipeline, " +
            "t.parameters, t.enabled, t.cron_expression, t.time_zone, t.secret_rotated_at, " +
            "t.created_by, t.created_at, t.updated_by, t.updated_at FROM pipeline_trigger t " +
            "JOIN pipeline_definition d ON d.id = t.definition_id " +
            "JOIN pipeline_artifact a ON a.id = d.artifact_id"
  }
}
