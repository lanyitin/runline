package dev.lawlan.runline.engine.resource

import java.time.ZoneOffset
import javax.sql.DataSource

class PostgresResourceBindingStore(private val dataSource: DataSource) : ResourceBindingStore {
  override fun forPipelines(
      keys: Collection<PipelineKey>
  ): Map<PipelineKey, Map<String, RoleBinding>> {
    val found = LinkedHashMap<PipelineKey, MutableMap<String, RoleBinding>>()
    keys.distinct().forEach { found[it] = LinkedHashMap() }
    if (found.isEmpty()) return found
    dataSource.connection.use { connection ->
      connection
          .prepareStatement(
              "SELECT uploader, pipeline, role, resource, set_by, set_at FROM resource_binding " +
                  "WHERE (uploader, pipeline) IN (SELECT * FROM unnest(?::text[], ?::text[])) " +
                  "ORDER BY uploader, pipeline, role"
          )
          .use {
            it.setArray(
                1,
                connection.createArrayOf("text", found.keys.map { k -> k.uploader }.toTypedArray()),
            )
            it.setArray(
                2,
                connection.createArrayOf("text", found.keys.map { k -> k.pipeline }.toTypedArray()),
            )
            it.executeQuery().use { rows ->
              while (rows.next()) {
                val binding =
                    RoleBinding(
                        rows.getString("uploader"),
                        rows.getString("pipeline"),
                        rows.getString("role"),
                        rows.getString("resource"),
                        rows.getString("set_by"),
                        rows.getObject("set_at", java.time.OffsetDateTime::class.java).toInstant(),
                    )
                found.getValue(binding.key)[binding.role] = binding
              }
            }
          }
    }
    return found
  }

  override fun put(binding: RoleBinding) {
    dataSource.connection.use { connection ->
      connection
          .prepareStatement(
              "INSERT INTO resource_binding (uploader, pipeline, role, resource, set_by, set_at) " +
                  "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (uploader, pipeline, role) DO UPDATE SET " +
                  "resource = EXCLUDED.resource, set_by = EXCLUDED.set_by, set_at = EXCLUDED.set_at"
          )
          .use {
            it.setString(1, binding.uploader)
            it.setString(2, binding.pipeline)
            it.setString(3, binding.role)
            it.setString(4, binding.resource)
            it.setString(5, binding.setBy)
            it.setObject(6, binding.setAt.atOffset(ZoneOffset.UTC))
            it.executeUpdate()
          }
    }
  }

  override fun remove(key: PipelineKey, role: String): Boolean =
      dataSource.connection.use { connection ->
        connection
            .prepareStatement(
                "DELETE FROM resource_binding WHERE uploader = ? AND pipeline = ? AND role = ?"
            )
            .use {
              it.setString(1, key.uploader)
              it.setString(2, key.pipeline)
              it.setString(3, role)
              it.executeUpdate() == 1
            }
      }
}
