package dev.lawlan.runline.engine.support

import dev.lawlan.runline.engine.config.DatabaseConfig
import dev.lawlan.runline.engine.db.DatabaseMigrator
import io.ktor.server.testing.*

/** Tokens used by the tests; each belongs to a name and role as configured in [API_TOKENS]. */
object TestTokens {
  const val ALICE = "tok-alice-0123456789"
  const val BOB = "tok-bob-0123456789"
  const val ROOT = "tok-root-0123456789"
  const val API_TOKENS = "alice:developer:$ALICE,bob:developer:$BOB,root:admin:$ROOT"
}

/**
 * The directory a test Engine is given as the code of every run: copies of the jars the Runner,
 * core and the Kotlin library are loaded from, the same three kinds of jar a deployment provides.
 */
object TestRuntimeDir {
  val path: java.nio.file.Path by lazy {
    val dir = java.nio.file.Files.createTempDirectory("run-runtime")
    TestRuntime.jars.forEach { java.nio.file.Files.copy(it, dir.resolve(it.fileName.toString())) }
    dir
  }
}

/** A new, migrated database on the shared PostgreSQL instance. */
fun migratedDatabase(): DatabaseConfig =
    PostgresTestContainer.newDatabase().also { DatabaseMigrator(it).migrate() }

/**
 * Starts the real application with its real `application.yaml` modules, supplying through
 * configuration overrides what the environment supplies in production: a real database, tokens and
 * the rest. [overrides] replace or add settings (a blank value counts as not set).
 */
fun ApplicationTestBuilder.configureEngine(
    database: DatabaseConfig = migratedDatabase(),
    overrides: Map<String, String> = emptyMap(),
) {
  configure {
    put("postgres.url", database.url)
    put("postgres.user", database.user)
    put("postgres.password", database.password)
    put("auth.tokens", TestTokens.API_TOKENS)
    val work = java.nio.file.Files.createTempDirectory("engine-work")
    put("workspace.sharedRoot", work.resolve("shared").toString())
    put("workspace.runRoot", work.resolve("runs").toString())
    put("workspace.maxBytes", "1000000")
    put("workspace.failedRunRetentionSeconds", "3600")
    put("runs.maxConcurrent", "2")
    put("runs.runtimeDir", TestRuntimeDir.path.toString())
    put("runs.shutdownGraceSeconds", "5")
    putAll(overrides)
  }
}
