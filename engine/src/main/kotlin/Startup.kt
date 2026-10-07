package dev.lawlan.runline.engine

import dev.lawlan.runline.engine.allowlist.AllowListAdmin
import dev.lawlan.runline.engine.config.EngineConfig
import dev.lawlan.runline.engine.config.RunRuntime
import dev.lawlan.runline.engine.db.DatabaseMigrator
import dev.lawlan.runline.engine.secret.SecretStore
import io.ktor.server.application.*
import io.ktor.server.plugins.di.*

/**
 * Fails fast at startup: invalid configuration, a database whose schema has not been migrated
 * (migrations are a separate one-off process, see `db/Migrate.kt`; the Engine never migrates), or a
 * run runtime directory that does not hold what runs need. Also seeds the allow list on the first
 * start (WI-10).
 */
fun Application.configureStartupChecks() {
  val config: EngineConfig by dependencies
  DatabaseMigrator(config.database).requireUpToDate()
  // The first start gives the allow list its initial content; later starts leave it alone.
  val admin: AllowListAdmin by dependencies
  admin.initialize(config.initialAllowList)
  // A keystore that is configured and cannot be opened stops the start (WI-41); the category of the
  // failure is all it says. The store is opened when it is first used, which is here.
  val secrets: SecretStore by dependencies
  secrets.entries()
  // A run's class loader must get the Runner, core and Kotlin jars and nothing of the Engine.
  RunRuntime.fromDirectory(config.runs.runtimeDir)
}
