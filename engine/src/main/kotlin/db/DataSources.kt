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

/**
 * A data source for the readiness probe: the same database, and a limit of [timeoutSeconds] on
 * connecting and on every answer, so that a database that is gone, or one that takes a connection
 * and does not answer, makes the probe fail in time instead of hanging it.
 */
fun probeDataSourceOf(database: DatabaseConfig, timeoutSeconds: Int): DataSource =
    PGSimpleDataSource().apply {
      setURL(database.url)
      user = database.user
      password = database.password
      loginTimeout = timeoutSeconds
      connectTimeout = timeoutSeconds
      socketTimeout = timeoutSeconds
    }
