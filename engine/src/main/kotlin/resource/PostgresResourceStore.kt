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
  ): SharedResource? =
      dataSource.connection.use { connection ->
        connection
            .prepareStatement(
                "UPDATE shared_resource SET capacity = COALESCE(?, capacity), " +
                    "enabled = COALESCE(?, enabled), updated_by = ?, updated_at = ? " +
                    "WHERE name = ? RETURNING $COLUMNS"
            )
            .use {
              it.setObject(1, capacity)
              it.setObject(2, enabled)
              it.setString(3, by)
              it.setObject(4, at.atOffset(ZoneOffset.UTC))
              it.setString(5, name)
              it.executeQuery().use { rows -> rows.all().singleOrNull() }
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
          )
      )
    }
  }

  private companion object {
    const val COLUMNS =
        "name, capacity, enabled, created_by, created_at, updated_by, updated_at, type, " +
            "settings::text AS settings, secret_alias"
    const val SELECT = "SELECT $COLUMNS FROM shared_resource"
  }
}
