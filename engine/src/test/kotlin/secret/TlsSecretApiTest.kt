package dev.lawlan.runline.engine.secret

import dev.lawlan.runline.accessors.tls.TestPki
import dev.lawlan.runline.engine.support.Keystores
import dev.lawlan.runline.engine.support.ResourceApiSupport
import dev.lawlan.runline.engine.support.TestTokens
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64
import kotlin.test.*
import kotlinx.serialization.json.*

/**
 * The certificate entries through the secret endpoints (WI-52): a real keystore with a trusted
 * certificate, a private key whose certificate is about to expire and a secret, real resources that
 * name them. Listed with what may be shown of each certificate, never anything of a key; the users
 * of an alias include the resources that use it as a certificate.
 */
class TlsSecretApiTest : ResourceApiSupport() {
  private val keystores = Keystores()
  private val passwordFile = keystores.passwordFile()
  private val pki = TestPki()
  private val authority = pki.authority("Internal CA")
  private val shortLived = pki.issue(authority, "app-client", days = 10)

  private fun keystore(): Path {
    val file = keystores.pkcs12("tls-secrets.p12", mapOf("api-key" to "sk-marker-tls-secret"))
    pki.addTrusted(file, "internal-ca", authority, passwordFile)
    pki.addPrivateKey(file, "app-client", shortLived, passwordFile)
    return file
  }

  private fun ApplicationTestBuilder.engineWith(file: Path) =
      engine("secrets.keystorePath" to "$file", "secrets.passwordFile" to "$passwordFile")

  private suspend fun ApplicationTestBuilder.secrets() =
      client.get("/api/v1/secrets") { bearer(TestTokens.ROOT)() }

  private suspend fun ApplicationTestBuilder.defineUsers() {
    define(
        "llm",
        1,
        """"type":"openai-compatible","settings":{"baseUrl":"https://llm.internal/v1","trustAliases":["internal-ca"],"clientCertAlias":"app-client"},"secretAlias":"api-key"""",
    )
    define(
        "db",
        1,
        """"type":"jdbc-pool","settings":{"kind":"postgresql","host":"db.internal","database":"app","username":"app","trustAliases":["internal-ca"]}""",
    )
  }

  @Test
  fun `certificate entries are listed with their certificates, warnings and users, secrets without`() =
      testApplication {
        engineWith(keystore())
        defineUsers()

        val response = secrets()

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val listed = response.json().array("secrets").associateBy { it.text("alias") }
        assertEquals("""["db","llm"]""", listed.getValue("internal-ca")["usedBy"].toString())
        assertEquals("""["llm"]""", listed.getValue("app-client")["usedBy"].toString())
        assertFalse("certificates" in listed.getValue("api-key"))
        val ca = listed.getValue("internal-ca").array("certificates").single()
        assertEquals(setOf("subject", "notAfter", "daysLeft", "fingerprint", "expiry"), ca.keys)
        assertEquals("CN=Internal CA", ca.text("subject"))
        assertEquals("valid", ca.text("expiry"))
        val chain = listed.getValue("app-client").array("certificates")
        assertEquals(listOf("CN=app-client", "CN=Internal CA"), chain.map { it.text("subject") })
        assertEquals("expiring", chain.first().text("expiry"))
        assertTrue(chain.first().text("daysLeft").toInt() in 8..10)
      }

  @Test
  fun `nothing of a private key, the secret or the keystore password is in any answer`() =
      testApplication {
        val file = keystore()
        engineWith(file)
        defineUsers()

        val bodies =
            listOf(
                    secrets(),
                    client.post("/api/v1/secrets/reload") { bearer(TestTokens.ROOT)() },
                    client.get("/api/v1/resources") { bearer(TestTokens.ROOT)() },
                    client.post("/api/v1/resources/llm/check") { bearer(TestTokens.ROOT)() },
                    client.post("/api/v1/resources/db/check") { bearer(TestTokens.ROOT)() },
                )
                .map { it.bodyAsText() }

        val key = shortLived.key.encoded
        val markers =
            listOf(
                "sk-marker-tls-secret",
                Keystores.DEFAULT_PASSWORD,
                Base64.getEncoder().encodeToString(key).take(32),
                Base64.getMimeEncoder().encodeToString(key).take(32),
                java.util.HexFormat.of().formatHex(key).take(32),
            )
        for (body in bodies) {
          for (marker in markers) assertFalse(body.contains(marker), "$marker in $body")
        }
      }

  @Test
  fun `a reload names a replaced certificate with the resources that use it`() = testApplication {
    val file = keystore()
    engineWith(file)
    defineUsers()

    val copy = file.resolveSibling("tls-secrets.next.p12")
    Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING)
    keystores.deleteEntry(copy, "internal-ca", passwordFile)
    pki.addTrusted(copy, "internal-ca", pki.authority("Next CA"), passwordFile)
    Files.move(copy, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    val response = client.post("/api/v1/secrets/reload") { bearer(TestTokens.ROOT)() }

    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals(
        """[{"alias":"internal-ca","usedBy":["db","llm"]}]""",
        response.json()["changed"].toString(),
    )
  }
}
