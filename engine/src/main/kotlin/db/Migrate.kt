package dev.lawlan.runline.engine.db

import dev.lawlan.runline.engine.config.DatabaseConfig
import io.ktor.server.config.*
import kotlin.system.exitProcess

/**
 * Applies pending schema migrations to the database named by [config] and returns how many were
 * applied. Needs only the `postgres.*` settings, the same ones the Engine reads.
 */
fun runMigration(config: ApplicationConfig): Int =
    DatabaseMigrator(DatabaseConfig.from(config)).migrate()

/**
 * One-off admin process (12-factor): same code and configuration as the Engine, run before the new
 * version starts. `./gradlew :engine:migrate` or `java -cp <engine classpath>
 * dev.lawlan.runline.engine.db.MigrateKt`.
 */
fun main() {
  val applied =
      try {
        runMigration(ConfigLoader.load())
      } catch (e: Exception) {
        System.err.println("Migration failed: ${e.message}")
        exitProcess(1)
      }
  println("Applied $applied migration(s)")
}
