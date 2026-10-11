package dev.lawlan.runline.engine.oidc

import dev.lawlan.runline.engine.auth.Role
import dev.lawlan.runline.engine.config.ConfigurationException
import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.support.TestTokens
import dev.lawlan.runline.engine.support.configureEngine
import dev.lawlan.runline.engine.support.migratedDatabase
import io.ktor.server.testing.*
import java.time.Instant
import kotlin.test.*

/**
 * A name is one identity, whether it came from a token or from a sign-in (ADR-029, 3): an Engine
 * whose tokens use the name of somebody who signed in does not start.
 */
class TokenNameCollisionStartupTest {
  private val database = migratedDatabase()

  private fun personSignedIn(name: String) {
    val now = Instant.now()
    PostgresSessionStore(dataSourceOf(database))
        .signIn(
            SignInIdentity("https://auth.example.test/", "sub-$name", name, name),
            NewSession(
                name.toByteArray(),
                Role.DEVELOPER,
                "csrf",
                byteArrayOf(1),
                now,
                now.plusSeconds(60),
            ),
            now,
        )
  }

  private fun causes(e: Throwable) = generateSequence(e) { it.cause }

  @Test
  fun `a token with the name of a person who signed in stops the start, and only says the name`() {
    personSignedIn("alice")

    val e = assertFails {
      testApplication {
        configureEngine(database)
        startApplication()
      }
    }

    val refusal = causes(e).filterIsInstance<ConfigurationException>().first()
    assertTrue(refusal.message!!.contains("alice"), refusal.message)
    assertFalse(refusal.message!!.contains(TestTokens.ALICE), refusal.message)
    assertFalse(refusal.message!!.contains("bob"), refusal.message)
  }

  @Test
  fun `every name that is taken is said`() {
    personSignedIn("alice")
    personSignedIn("bob")

    val e = assertFails {
      testApplication {
        configureEngine(database)
        startApplication()
      }
    }

    val message = causes(e).filterIsInstance<ConfigurationException>().first().message!!
    assertTrue(message.contains("alice") && message.contains("bob"), message)
  }

  @Test
  fun `names of people who signed in that no token uses do not matter`() = testApplication {
    personSignedIn("carol")
    configureEngine(database)

    startApplication()
  }
}
