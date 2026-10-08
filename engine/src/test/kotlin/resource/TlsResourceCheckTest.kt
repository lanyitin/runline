package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.accessors.fake.FakeOpenAiServer
import dev.lawlan.runline.accessors.jdbc.TlsPostgres
import dev.lawlan.runline.accessors.tls.TestPki
import dev.lawlan.runline.engine.support.Keystores
import dev.lawlan.runline.engine.support.ResourceApiSupport
import dev.lawlan.runline.engine.support.TestTokens
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*

/**
 * Checking resources whose connections use the keystore's certificates (WI-52), through the whole
 * Engine: the Fake OpenAI service behind real TLS that requires a client certificate, a real
 * PostgreSQL that speaks TLS, real certificates made by keytool in a real keystore. A check reports
 * every certificate it uses, warns of one about to expire, and tells each failure of TLS apart by a
 * real handshake.
 */
class TlsResourceCheckTest : ResourceApiSupport() {
  private val keystores = Keystores()
  private val passwordFile = keystores.passwordFile()
  private val server =
      FakeOpenAiServer(
          tls = pki.serverContext(serviceIdentity, clientsOf = listOf(authority.certificate)),
          requireClientCertificate = true,
      )

  @AfterTest fun stop() = server.close()

  /** A keystore with the authority, another authority, and client certificates by alias. */
  private fun keystore(
      vararg clients: Pair<String, TestPki.Issued>,
      secrets: Map<String, String> = mapOf("api-key" to "sk-1"),
  ): Path {
    val file = keystores.pkcs12("check-${System.nanoTime()}.p12", secrets)
    pki.addTrusted(file, "internal-ca", authority, passwordFile)
    pki.addTrusted(file, "other-ca", otherAuthority, passwordFile)
    pki.addTrusted(file, "expired-ca", expiredAuthority, passwordFile)
    clients.forEach { (alias, issued) -> pki.addPrivateKey(file, alias, issued, passwordFile) }
    return file
  }

  private fun ApplicationTestBuilder.engineWith(file: Path) =
      engine(
          "secrets.keystorePath" to "$file",
          "secrets.passwordFile" to "$passwordFile",
          "resources.checkTimeoutSeconds" to "10",
      )

  private suspend fun ApplicationTestBuilder.defineOpenAi(
      name: String,
      tls: String,
      host: String = "localhost",
  ) {
    val response =
        define(
            name,
            1,
            """"type":"openai-compatible","settings":{"baseUrl":"${server.baseUrl(host)}"$tls}""",
        )
    assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
  }

  private suspend fun ApplicationTestBuilder.check(name: String): JsonObject {
    val response = client.post("/api/v1/resources/$name/check") { bearer(TestTokens.ROOT)() }
    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    return response.json()
  }

  private fun JsonObject.failure() = this["failure"]?.jsonPrimitive?.contentOrNull

  @Test
  fun `a check over TLS passes and reports every certificate it uses, nothing of a key`() =
      testApplication {
        engineWith(keystore("app-client" to clientIdentity))
        defineOpenAi("llm", ""","trustAliases":["internal-ca"],"clientCertAlias":"app-client"""")

        val body = check("llm")

        assertEquals(JsonPrimitive(true), body["ok"], "$body")
        assertEquals(
            listOf(
                listOf("internal-ca", "CN=Internal CA"),
                listOf("app-client", "CN=runline-client"),
                listOf("app-client", "CN=Internal CA"),
            ),
            body.array("certificates").map { listOf(it.text("alias"), it.text("subject")) },
        )
        val own = body.array("certificates")[1]
        assertEquals(setOf("alias", "subject", "notAfter", "daysLeft", "fingerprint"), own.keys)
        assertEquals(
            clientIdentity.certificate.notAfter.toInstant().toString(),
            own.text("notAfter"),
        )
        assertTrue(own.text("daysLeft").toInt() in 363..365, "$own")
        assertTrue(own.text("fingerprint").matches(Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}")))
        assertEquals(emptyList(), body.array("warnings"))
        assertEquals(1, server.requests.size)
        val text = body.toString()
        val key = java.util.Base64.getEncoder().encodeToString(clientIdentity.key.encoded)
        assertFalse(text.contains(key.take(40)))
      }

  @Test
  fun `each failure of TLS is its own category, found by a real handshake`() = testApplication {
    engineWith(keystore("app-client" to clientIdentity))
    defineOpenAi(
        "by-number",
        ""","trustAliases":["internal-ca"],"clientCertAlias":"app-client"""",
        "127.0.0.1",
    )
    defineOpenAi("other-trust", ""","trustAliases":["other-ca"],"clientCertAlias":"app-client"""")
    defineOpenAi("no-client", ""","trustAliases":["internal-ca"]""")
    defineOpenAi("default-trust", ""","clientCertAlias":"app-client"""")

    val lines =
        dev.lawlan.runline.engine.support.CapturedLogs().use { logs ->
          assertEquals("hostname_mismatch", check("by-number").failure())
          logs.lines
        }
    // The Engine's log says the category with the aliases of the certificates, nothing of them.
    val line = lines.single { it.contains("by-number") && it.contains("hostname_mismatch") }
    assertTrue(line.contains("internal-ca") && line.contains("app-client"), line)
    assertEquals("trust_failed", check("other-trust").failure())
    assertEquals("client_cert_rejected", check("no-client").failure())
    // Without trust aliases the JVM's default trust is used, which does not know the authority.
    assertEquals("trust_failed", check("default-trust").failure())
  }

  @Test
  fun `a certificate about to expire is warned about and the check still passes`() =
      testApplication {
        engineWith(keystore("short-client" to shortLived))
        defineOpenAi("llm", ""","trustAliases":["internal-ca"],"clientCertAlias":"short-client"""")

        val body = check("llm")

        assertEquals(JsonPrimitive(true), body["ok"], "$body")
        val warning = body.array("warnings").single()
        assertEquals("certificate_expiring", warning.text("warning"))
        assertEquals("short-client", warning.text("alias"))
        assertTrue(warning.text("daysLeft").toInt() in 8..10, "$warning")
      }

  @Test
  fun `an expired certificate fails the check as certificate_expired before anything is sent`() =
      testApplication {
        engineWith(keystore("app-client" to clientIdentity))
        defineOpenAi(
            "llm",
            ""","trustAliases":["internal-ca","expired-ca"],"clientCertAlias":"app-client"""",
        )

        val body = check("llm")

        assertEquals("certificate_expired", body.failure(), "$body")
        assertTrue(
            body.array("certificates").any {
              it.text("alias") == "expired-ca" && it.text("daysLeft").toInt() < 0
            }
        )
        assertEquals(0, server.requests.size)
      }

  @Test
  fun `an alias that is not in the keystore is alias_missing and nothing is sent`() =
      testApplication {
        engineWith(keystore())
        defineOpenAi("llm", ""","trustAliases":["internal-ca"],"clientCertAlias":"app-client"""")

        assertEquals("alias_missing", check("llm").failure())
        assertEquals(0, server.requests.size)
      }

  @Test
  fun `a database over TLS is checked the same way, the host and the client certificate included`() =
      testApplication {
        val role = "tls_${System.nanoTime()}"
        val password = "pw-${System.nanoTime()}"
        postgres.createRole(role, password, certificateRequired = true)
        engineWith(keystore("app-client" to clientIdentity, secrets = mapOf("db-pass" to password)))
        suspend fun defineDb(name: String, host: String, tls: String) {
          val response =
              define(
                  name,
                  1,
                  """"type":"jdbc-pool","settings":{"kind":"postgresql","host":"$host","port":${postgres.port},"database":"${postgres.database}","username":"$role"$tls},"secretAlias":"db-pass"""",
              )
          assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        }
        val both = ""","trustAliases":["internal-ca"],"clientCertAlias":"app-client""""
        defineDb("db", postgres.host, both)
        defineDb("db-by-number", "127.0.0.1", both)
        defineDb("db-no-client", postgres.host, ""","trustAliases":["internal-ca"]""")

        val passed = check("db")
        assertEquals(JsonPrimitive(true), passed["ok"], "$passed")
        assertEquals(3, passed.array("certificates").size)
        assertEquals("hostname_mismatch", check("db-by-number").failure())
        assertEquals("client_cert_rejected", check("db-no-client").failure())
      }

  private companion object {
    val pki = TestPki()
    val authority = pki.authority("Internal CA")
    val otherAuthority = pki.authority("Other CA")
    val expiredAuthority = pki.issue(authority, "Expired CA", days = 30, startDate = "-400d")
    val serviceIdentity = pki.issue(authority, "localhost", listOf("dns:localhost"))
    val clientIdentity = pki.issue(authority, "runline-client")
    val shortLived = pki.issue(authority, "runline-client", days = 10)
    val postgres: TlsPostgres by lazy {
      TlsPostgres(
          pki,
          pki.issue(authority, "localhost", listOf("dns:localhost")),
          listOf(authority.certificate),
      )
    }
  }
}
