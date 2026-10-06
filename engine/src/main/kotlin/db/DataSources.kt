package dev.lawlan.runline.engine.db

import dev.lawlan.runline.engine.config.DatabaseConfig
import javax.sql.DataSource
import org.postgresql.ds.PGSimpleDataSource

/**
 * The database named by [database], located by configuration only. Each call to `connection` opens
 * a new connection (no pooling yet); a pooled [DataSource] can replace this without touching the
 * stores.
 */
fun dataSourceOf(database: DatabaseConfig): DataSource =
    PGSimpleDataSource().apply {
      setURL(database.url)
      user = database.user
      password = database.password
    }
