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
                        rows.getString("uploaded_by"),
                        rows.getString("pipeline"),
                        rows.getString("role"),
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
    // A role leads to the resource its uploader bound it to for the pipeline, else to the resource
    // of its own name (ADR-025).
    const val QUERY =
        "SELECT r.name AS resource, role.value AS role, a.content_hash, a.uploaded_by, " +
            "d.name AS pipeline, d.metadata -> 'resourceTypes' ->> role.value AS declared_type, " +
            "(SELECT count(*) FROM pipeline_trigger t WHERE t.definition_id = d.id) AS triggers " +
            "FROM unnest(?::text[]) AS r(name) " +
            "CROSS JOIN pipeline_definition d " +
            "CROSS JOIN LATERAL jsonb_array_elements_text(d.metadata -> 'resources') AS role(value) " +
            "JOIN pipeline_artifact a ON a.id = d.artifact_id " +
            "LEFT JOIN resource_binding b ON b.uploader = a.uploaded_by AND b.pipeline = d.name " +
            "AND b.role = role.value " +
            "WHERE COALESCE(b.resource, role.value) = r.name " +
            "ORDER BY r.name, a.uploaded_at, a.content_hash, a.uploaded_by, d.name, role.value"
  }
}
