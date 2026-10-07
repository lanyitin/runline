package dev.lawlan.runline.engine.resource

import javax.sql.DataSource

class PostgresResourceDeclarationStore(private val dataSource: DataSource) :
    ResourceDeclarationStore {
  override fun declaredBy(names: Collection<String>): Map<String, ResourceDeclarations> {
    if (names.isEmpty()) return emptyMap()
    val found = LinkedHashMap<String, MutableList<DeclaringDefinition>>()
    names.distinct().forEach { found[it] = mutableListOf() }
    dataSource.connection.use { connection ->
      connection.prepareStatement(QUERY).use {
        it.setArray(1, connection.createArrayOf("text", names.distinct().toTypedArray()))
        it.executeQuery().use { rows ->
          while (rows.next()) {
            found
                .getValue(rows.getString("resource"))
                .add(
                    DeclaringDefinition(
                        rows.getString("content_hash"),
                        rows.getString("pipeline"),
                        rows.getString("declared_type"),
                        rows.getInt("triggers"),
                    )
                )
          }
        }
      }
    }
    return found.mapValues { ResourceDeclarations(it.value) }
  }

  private companion object {
    // jsonb_exists is the function behind the `?` operator, which a JDBC statement cannot carry.
    const val QUERY =
        "SELECT r.name AS resource, a.content_hash, d.name AS pipeline, " +
            "d.metadata -> 'resourceTypes' ->> r.name AS declared_type, " +
            "(SELECT count(*) FROM pipeline_trigger t WHERE t.definition_id = d.id) AS triggers " +
            "FROM unnest(?::text[]) AS r(name) " +
            "JOIN pipeline_definition d ON jsonb_exists(d.metadata -> 'resources', r.name) " +
            "JOIN pipeline_artifact a ON a.id = d.artifact_id " +
            "ORDER BY r.name, a.uploaded_at, a.content_hash, d.name"
  }
}
