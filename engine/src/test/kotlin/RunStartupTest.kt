package dev.lawlan.runline.engine

import dev.lawlan.runline.accessors.support.TestDirectories
import dev.lawlan.runline.engine.db.SchemaOutOfDateException
import dev.lawlan.runline.engine.support.PostgresTestContainer
import dev.lawlan.runline.engine.support.configureEngine
import io.ktor.server.testing.*
import kotlin.test.*
import org.flywaydb.core.Flyway

/** The Engine refuses to start when what runs need is missing or inconsistent. */
class RunStartupTest {
  private fun causes(e: Throwable) = generateSequence(e) { it.cause }

  @Test
  fun `refuses to start without a concurrency limit and names the setting`() {
    val e = assertFails {
      testApplication {
        configureEngine(overrides = mapOf("runs.maxConcurrent" to ""))
        startApplication()
      }
    }

    assertTrue(causes(e).any { it.message?.contains("runs.maxConcurrent") == true }, "$e")
  }

  @Test
  fun `refuses to start without a place for run directories and names the setting`() {
    val e = assertFails {
      testApplication {
        configureEngine(overrides = mapOf("workspace.sharedRoot" to ""))
        startApplication()
      }
    }

    assertTrue(causes(e).any { it.message?.contains("workspace.sharedRoot") == true }, "$e")
  }

  @Test
  fun `refuses to start when the run runtime directory lacks the jars a run needs`() {
    val empty = TestDirectories.forThisTest("empty-runtime")

    val e = assertFails {
      testApplication {
        configureEngine(overrides = mapOf("runs.runtimeDir" to empty.toString()))
        startApplication()
      }
    }

    assertTrue(causes(e).any { it.message?.contains("runs.runtimeDir") == true }, "$e")
  }

  @Test
  fun `refuses to start when the run tables have not been migrated`() {
    val database = PostgresTestContainer.newDatabase()
    // The schema as it was before runs existed: migrated, but not to the latest version.
    Flyway.configure()
        .dataSource(database.url, database.user, database.password)
        .locations("classpath:db/migration")
        .target("1")
        .load()
        .migrate()

    val e = assertFails {
      testApplication {
        configureEngine(database = database)
        startApplication()
      }
    }

    assertTrue(causes(e).any { it is SchemaOutOfDateException }, "$e")
  }
}
