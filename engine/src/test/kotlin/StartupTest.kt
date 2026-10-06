package dev.lawlan.runline.engine

import dev.lawlan.runline.engine.db.SchemaOutOfDateException
import dev.lawlan.runline.engine.support.PostgresTestContainer
import dev.lawlan.runline.engine.support.configureEngine
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*

/** The Engine refuses to start rather than run with missing or inconsistent setup. */
class StartupTest {
  private fun rootCause(e: Throwable): Throwable = e.cause?.let { rootCause(it) } ?: e

  @Test
  fun `refuses to start without tokens and names the setting`() {
    val e = assertFails {
      testApplication {
        configureEngine(overrides = mapOf("auth.tokens" to ""))
        startApplication()
      }
    }

    assertTrue(
        generateSequence(e) { it.cause }.any { it.message?.contains("auth.tokens") == true },
        "$e",
    )
  }

  @Test
  fun `refuses to start when the database is not migrated`() {
    val e = assertFails {
      testApplication {
        configureEngine(database = PostgresTestContainer.newDatabase())
        startApplication()
      }
    }

    assertTrue(generateSequence(e) { it.cause }.any { it is SchemaOutOfDateException }, "$e")
  }

  @Test
  fun `refuses to start without a database setting`() {
    val e = assertFails {
      testApplication {
        configureEngine(overrides = mapOf("postgres.url" to ""))
        startApplication()
      }
    }

    assertTrue(
        generateSequence(e) { it.cause }.any { it.message?.contains("postgres.url") == true },
        "$e",
    )
  }

  @Test
  fun `starts with complete configuration and serves the open endpoints without a token`() =
      testApplication {
        configureEngine()

        assertEquals(HttpStatusCode.OK, client.get("/openapi").status)
      }
}
