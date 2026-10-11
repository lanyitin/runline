package dev.lawlan.runline.engine.oidc

import dev.lawlan.runline.engine.config.ConfigurationException
import dev.lawlan.runline.engine.support.configureEngine
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*

/**
 * What the Engine does with the settings of OIDC login as it starts (WI-1200, 1): a wrong set stops
 * it and every problem is said; a right one starts it without asking the provider anything.
 */
class OidcStartupTest {
  private val key = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="

  private val complete =
      mapOf(
          "oidc.issuer" to "http://localhost:1/realms/none",
          "oidc.clientId" to "runline",
          "oidc.clientSecret" to "client-secret-value",
          "oidc.publicBaseUrl" to "http://localhost:8080",
          "session.encryptionKey" to key,
      )

  private fun causes(e: Throwable) = generateSequence(e) { it.cause }

  private fun refusal(overrides: Map<String, String>): String {
    val e = assertFails {
      testApplication {
        configureEngine(overrides = overrides)
        startApplication()
      }
    }
    return causes(e).filterIsInstance<ConfigurationException>().first().message!!
  }

  @Test
  fun `a part of the settings stops the Engine and every missing one is said`() {
    val message = refusal(mapOf("oidc.issuer" to "https://auth.example.test/"))

    for (setting in
        listOf(
            "oidc.clientId",
            "oidc.clientSecret",
            "oidc.publicBaseUrl",
            "session.encryptionKey",
        )) {
      assertTrue(message.contains(setting), message)
    }
  }

  @Test
  fun `two sources of the client secret, a bad key and equal groups are all said at once`() {
    val message =
        refusal(
            complete +
                mapOf(
                    "oidc.clientSecretFile" to "/run/secrets/none",
                    "session.encryptionKey" to "short",
                    "oidc.adminGroup" to "team",
                    "oidc.developerGroup" to "team",
                    "session.idleSeconds" to "100",
                    "session.absoluteSeconds" to "50",
                )
        )

    for (problem in
        listOf(
            "oidc.clientSecret and oidc.clientSecretFile",
            "session.encryptionKey must be 32 bytes",
            "oidc.adminGroup and oidc.developerGroup",
            "session.idleSeconds must not be longer",
        )) {
      assertTrue(message.contains(problem), "$problem in: $message")
    }
    assertFalse(message.contains("client-secret-value") || message.contains("short"), message)
  }

  @Test
  fun `a complete set starts the Engine without asking the provider, which is not there`() =
      testApplication {
        configureEngine(overrides = complete)
        startApplication()

        // The provider at the issuer address does not exist; start and readiness do not depend on
        // it.
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/health/ready").status)
      }
}
