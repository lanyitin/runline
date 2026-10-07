package dev.lawlan.runline.engine.resource

import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.sql.DataSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class PostgresResourceStore(private val dataSource: DataSource) : ResourceStore {
  override fun insert(resource: SharedResource): Boolean =
      dataSource.connection.use { connection ->
        connection
            .prepareStatement(
                "INSERT INTO shared_resource (name, capacity, enabled, created_by, created_at, " +
                    "updated_by, updated_at, type, settings, secret_alias) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?) " +
                    "ON CONFLICT (name) DO NOTHING"
            )
            .use {
              it.setString(1, resource.name)
              it.setInt(2, resource.capacity)
              it.setBoolean(3, resource.enabled)
              it.setString(4, resource.createdBy)
              it.setObject(5, resource.createdAt.atOffset(ZoneOffset.UTC))
              it.setString(6, resource.updatedBy)
              it.setObject(7, resource.updatedAt.atOffset(ZoneOffset.UTC))
              it.setString(8, resource.type.wireName)
              it.setString(9, Json.encodeToString(JsonObject.serializer(), resource.settings))
              it.setString(10, resource.secretAlias)
              it.executeUpdate() == 1
            }
      }

  override fun find(name: String): SharedResource? = findAll(listOf(name))[name]

  override fun findAll(names: Collection<String>): Map<String, SharedResource> {
    if (names.isEmpty()) return emptyMap()
    return dataSource.connection.use { connection ->
      connection.prepareStatement("$SELECT WHERE name = ANY (?)").use {
        it.setArray(1, connection.createArrayOf("text", names.distinct().toTypedArray()))
        it.executeQuery().use { rows -> rows.all().associateBy { r -> r.name } }
      }
    }
  }

  override fun list(): List<SharedResource> =
      dataSource.connection.use { connection ->
        connection.prepareStatement("$SELECT ORDER BY name").use {
          it.executeQuery().use { rows -> rows.all() }
        }
      }

  override fun update(
      name: String,
      capacity: Int?,
      enabled: Boolean?,
      by: String,
      at: Instant,
      settings: JsonObject?,
  ): SharedResource? =
      dataSource.connection.use { connection ->
        connection
            .prepareStatement(
                "UPDATE shared_resource SET capacity = COALESCE(?, capacity), " +
                    "enabled = COALESCE(?, enabled), settings = COALESCE(?::jsonb, settings), " +
                    "check_ok = CASE WHEN $CHANGED THEN NULL ELSE check_ok END, " +
                    "check_failure = CASE WHEN $CHANGED THEN NULL ELSE check_failure END, " +
                    "checked_at = CASE WHEN $CHANGED THEN NULL ELSE checked_at END, " +
                    "updated_by = ?, updated_at = ? " +
                    "WHERE name = ? RETURNING $COLUMNS"
            )
            .use {
              it.setObject(1, capacity)
              it.setObject(2, enabled)
              it.setString(
                  3,
                  settings?.let { s -> Json.encodeToString(JsonObject.serializer(), s) },
              )
              val encoded = settings?.let { s -> Json.encodeToString(JsonObject.serializer(), s) }
              for (index in 4..9) it.setString(index, encoded)
              it.setString(10, by)
              it.setObject(11, at.atOffset(ZoneOffset.UTC))
              it.setString(12, name)
              it.executeQuery().use { rows -> rows.all().singleOrNull() }
            }
      }

  override fun recordCheck(
      name: String,
      result: CheckResult,
      settings: JsonObject,
      secretAlias: String?,
  ): Boolean =
      dataSource.connection.use { connection ->
        connection
            .prepareStatement(
                "UPDATE shared_resource SET check_ok = ?, check_failure = ?, checked_at = ? " +
                    "WHERE name = ? AND settings = ?::jsonb AND secret_alias IS NOT DISTINCT FROM ?"
            )
            .use {
              it.setBoolean(1, result.ok)
              it.setString(2, result.failure?.wire)
              it.setObject(3, result.checkedAt.atOffset(ZoneOffset.UTC))
              it.setString(4, name)
              it.setString(5, Json.encodeToString(JsonObject.serializer(), settings))
              it.setString(6, secretAlias)
              it.executeUpdate() == 1
            }
      }

  override fun delete(name: String): Boolean =
      dataSource.connection.use { connection ->
        connection.prepareStatement("DELETE FROM shared_resource WHERE name = ?").use {
          it.setString(1, name)
          it.executeUpdate() == 1
        }
      }

  private fun ResultSet.all(): List<SharedResource> = buildList {
    while (next()) {
      add(
          SharedResource(
              name = getString("name"),
              capacity = getInt("capacity"),
              enabled = getBoolean("enabled"),
              createdBy = getString("created_by"),
              createdAt = getObject("created_at", OffsetDateTime::class.java).toInstant(),
              updatedBy = getString("updated_by"),
              updatedAt = getObject("updated_at", OffsetDateTime::class.java).toInstant(),
              type = checkNotNull(ResourceType.fromWireName(getString("type"))),
              settings = Json.decodeFromString(JsonObject.serializer(), getString("settings")),
              secretAlias = getString("secret_alias"),
              lastCheck =
                  getObject("checked_at", OffsetDateTime::class.java)?.let {
                    CheckResult(
                        getBoolean("check_ok"),
                        getString("check_failure")?.let(CheckFailure::fromWire),
                        it.toInstant(),
                    )
                  },
          )
      )
    }
  }

  private companion object {
    const val COLUMNS =
        "name, capacity, enabled, created_by, created_at, updated_by, updated_at, type, " +
            "settings::text AS settings, secret_alias, check_ok, check_failure, checked_at"
    /** The settings given differ from the stored ones: what a check was made of no longer holds. */
    const val CHANGED = "(?::jsonb IS NOT NULL AND ?::jsonb IS DISTINCT FROM settings)"
    const val SELECT = "SELECT $COLUMNS FROM shared_resource"
  }
}
