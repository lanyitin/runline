package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.accessors.tls.TestPki
import dev.lawlan.runline.engine.support.Keystores
import dev.lawlan.runline.engine.support.ResourceApiSupport
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*

/**
 * The certificates of `openai-compatible` and `jdbc-pool` resources through the whole Engine
 * (WI-52): a real PostgreSQL, a real keystore with a secret, a trusted certificate and a private
 * key made by keytool. An alias names an entry of the kind its member wants, or the definition is
 * refused; its status against the keystore is shown and nothing of what it holds.
 */
class TlsResourceApiTest : ResourceApiSupport() {
  private val keystores = Keystores()
  private val passwordFile = keystores.passwordFile()
  private val pki = TestPki()
  private val authority = pki.authority("Internal CA")

  private fun keystore(): Path {
    val file = keystores.pkcs12("tls-${System.nanoTime()}.p12", mapOf("api-key" to "sk-marker-1"))
    pki.addTrusted(file, "internal-ca", authority, passwordFile)
    pki.addPrivateKey(file, "app-client", pki.issue(authority, "app-client"), passwordFile)
    return file
  }

  private fun ApplicationTestBuilder.engineWith(file: Path) =
      engine("secrets.keystorePath" to "$file", "secrets.passwordFile" to "$passwordFile")

  private suspend fun ApplicationTestBuilder.defineOpenAi(
      name: String,
      tls: String,
      alias: String? = null,
      base: String = "https://llm.internal/v1",
  ) =
      define(
          name,
          1,
          """"type":"openai-compatible","settings":{"baseUrl":"$base"$tls}""" +
              (alias?.let { ""","secretAlias":"$it"""" } ?: ""),
      )

  private suspend fun ApplicationTestBuilder.defineJdbc(
      name: String,
      tls: String,
      alias: String? = null,
  ) =
      define(
          name,
          1,
          """"type":"jdbc-pool","settings":{"kind":"postgresql","host":"db.internal","database":"app","username":"app"$tls}""" +
              (alias?.let { ""","secretAlias":"$it"""" } ?: ""),
      )

  private suspend fun HttpResponse.problem(): String {
    assertEquals(HttpStatusCode.UnprocessableEntity, status, bodyAsText())
    return json().text("problem")
  }

  @Test
  fun `aliases of the right kinds are accepted and each has its status`() = testApplication {
    engineWith(keystore())

    val created =
        defineOpenAi(
            "secure-llm",
            ""","trustAliases":["Internal-CA","not-there"],"clientCertAlias":"app-client"""",
            alias = "api-key",
        )

    assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
    val body = created.json()
    assertEquals(
        """["internal-ca","not-there"]""",
        body["settings"]!!.jsonObject["trustAliases"].toString(),
    )
    assertEquals(
        """[{"alias":"internal-ca","status":"found"},{"alias":"not-there","status":"missing"}]""",
        body["trustStatus"].toString(),
    )
    assertEquals("found", body.text("clientCertStatus"))
    assertEquals("found", body.text("secretStatus"))
    val plain = defineJdbc("plain-db", "").json()
    assertEquals("[]", plain["trustStatus"].toString())
    assertEquals("not_set", plain.text("clientCertStatus"))
  }

  @Test
  fun `an alias of another kind is refused as alias_wrong_type, for every member and both types`() =
      testApplication {
        engineWith(keystore())

        assertEquals(
            "alias_wrong_type",
            defineOpenAi("a", ""","trustAliases":["api-key"]""").problem(),
        )
        assertEquals(
            "alias_wrong_type",
            defineOpenAi("b", ""","clientCertAlias":"internal-ca"""").problem(),
        )
        assertEquals("alias_wrong_type", defineOpenAi("c", "", alias = "internal-ca").problem())
        assertEquals(
            "alias_wrong_type",
            defineJdbc("d", ""","trustAliases":["app-client"]""").problem(),
        )
        assertEquals(
            "alias_wrong_type",
            defineJdbc("e", ""","clientCertAlias":"api-key"""").problem(),
        )
        assertEquals("alias_wrong_type", defineJdbc("f", "", alias = "app-client").problem())
        defineJdbc("g", "")
        assertEquals(
            "alias_wrong_type",
            change("g", """{"secretAlias":"internal-ca"}""").problem(),
        )
      }

  @Test
  fun `a plain http service cannot name certificates`() = testApplication {
    engineWith(keystore())

    assertEquals(
        "invalid_settings",
        defineOpenAi(
                "plain",
                ""","trustAliases":["internal-ca"]""",
                base = "http://llm.internal/v1",
            )
            .problem(),
    )
  }
}
