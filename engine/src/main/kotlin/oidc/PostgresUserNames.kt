package dev.lawlan.runline.engine.oidc

import javax.sql.DataSource

class PostgresUserNames(private val dataSource: DataSource) : UserNames {
  override fun taken(names: Collection<String>): Set<String> =
      dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT name FROM oidc_user WHERE name = ANY (?)").use {
          it.setArray(1, connection.createArrayOf("text", names.toTypedArray()))
          it.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
        }
      }
}
