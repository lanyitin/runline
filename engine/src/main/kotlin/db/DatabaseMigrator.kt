package dev.lawlan.runline.engine.db

import dev.lawlan.runline.engine.config.DatabaseConfig
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException

class SchemaOutOfDateException(message: String) : RuntimeException(message)

/**
 * Versioned, forward-only schema migrations (06-data-model). Run as a one-off admin process (see
 * `Migrate.kt`); the Engine itself only calls [requireUpToDate] at startup and never migrates.
 */
class DatabaseMigrator(database: DatabaseConfig) {
  private val flyway: Flyway =
      Flyway.configure()
          .dataSource(database.url, database.user, database.password)
          .locations("classpath:db/migration")
          .load()

  /** Applies pending migrations and returns how many were applied. */
  fun migrate(): Int = flyway.migrate().migrationsExecuted

  /** Fails with [SchemaOutOfDateException] unless every migration has been applied. */
  fun requireUpToDate() {
    try {
      flyway.validate()
    } catch (e: FlywayException) {
      throw SchemaOutOfDateException(
          "Database schema is out of date or does not match this Engine. " +
              "Run the migration process before starting the Engine. (${e.message?.lineSequence()?.first()})"
      )
    }
  }
}
