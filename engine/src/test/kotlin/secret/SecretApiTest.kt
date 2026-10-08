package dev.lawlan.runline.engine.secret

import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.resource.PostgresResourceStore
import dev.lawlan.runline.engine.resource.ResourceType
import dev.lawlan.runline.engine.resource.SharedResource
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
import java.time.Instant
import kotlin.test.*
import kotlinx.serialization.json.*

/** The secret endpoints through the whole Engine: real PostgreSQL and a real keystore (WI-41). */
class SecretApiTest : ResourceApiSupport() {
  private val keystores = Keystores()
  private val passwordFile = keystores.passwordFile()

  private fun ApplicationTestBuilder.engineWith(file: Path?) =
      if (file == null) engine()
      else
          engine(
              "secrets.keystorePath" to "$file",
              "secrets.passwordFile" to "$passwordFile",
          )

  private suspend fun ApplicationTestBuilder.secrets(token: String? = TestTokens.ROOT) =
      client.get("/api/v1/secrets") { bearer(token)() }

  private suspend fun ApplicationTestBuilder.reload(token: String? = TestTokens.ROOT) =
      client.post("/api/v1/secrets/reload") { bearer(token)() }

  private fun replaceWith(file: Path, change: (Path) -> Unit) {
    val copy = file.resolveSibling(file.fileName.toString() + ".new")
    Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING)
    change(copy)
    Files.move(copy, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
  }

  private fun resourceUsing(name: String, alias: String) {
    val now = Instant.now()
    PostgresResourceStore(dataSourceOf(database))
        .insert(
            SharedResource(
                name,
                1,
                true,
                "root",
                now,
                "root",
                now,
                ResourceType.COUNTER,
                secretAlias = alias,
            )
        )
  }

  @Test
  fun `the aliases are listed with their type, status and users and nothing else`() =
      testApplication {
        val file =
            keystores.pkcs12("api.p12", mapOf("Db-Pass" to "marker-value-api", "bad" to "café"))
        keystores.trustedCertificate(file, "internal-ca", passwordFile)
        resourceUsing("warehouse", "db-pass")
        engineWith(file)

        val response = secrets()

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val listed = response.json().array("secrets")
        assertEquals(
            listOf(
                listOf("bad", "secret", "invalid_secret", "[]"),
                listOf("db-pass", "secret", "found", """["warehouse"]"""),
                listOf("internal-ca", "trusted_certificate", "found", "[]"),
            ),
            listed.map {
              listOf(it.text("alias"), it.text("type"), it.text("status"), "${it["usedBy"]}")
            },
        )
        // A certificate entry also says what may be shown of its certificates (WI-52).
        assertTrue(
            listed.all {
              it.keys ==
                  setOf("alias", "type", "status", "usedBy") +
                      (if (it.text("type") == "secret") emptySet() else setOf("certificates"))
            }
        )
        assertFalse(response.bodyAsText().contains("marker-value-api"))
      }

  @Test
  fun `without a keystore both endpoints answer 409 secret_store_not_configured`() =
      testApplication {
        engineWith(null)

        for (response in listOf(secrets(), reload())) {
          assertEquals(HttpStatusCode.Conflict, response.status)
          assertEquals("secret_store_not_configured", response.json().text("error"))
        }
      }

  @Test
  fun `a reload answers with the number of aliases and the changed ones with their users`() =
      testApplication {
        val file = keystores.pkcs12("reload.p12", mapOf("db-pass" to "old", "kept" to "same"))
        resourceUsing("warehouse", "db-pass")
        engineWith(file)
        startApplication()

        val unchanged = reload().json()
        replaceWith(file) {
          keystores.deleteEntry(it, "db-pass", passwordFile)
          keystores.importSecret(it, "db-pass", "new", passwordFile)
          keystores.importSecret(it, "extra", "added", passwordFile)
        }
        val response = reload()

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(JsonPrimitive(2), unchanged["aliases"])
        assertEquals(emptyList(), unchanged.array("changed"))
        val body = response.json()
        assertEquals(JsonPrimitive(3), body["aliases"])
        assertEquals(setOf("aliases", "changed"), body.keys)
        assertEquals(
            listOf("db-pass" to """["warehouse"]""", "extra" to "[]"),
            body.array("changed").map { it.text("alias") to "${it["usedBy"]}" },
        )
      }

  @Test
  fun `a reload of a file that cannot be read answers 422 with the category and keeps the secrets`() =
      testApplication {
        val file = keystores.pkcs12("broken.p12", mapOf("a" to "value"))
        engineWith(file)
        startApplication()
        val bytes = Files.readAllBytes(file)
        Files.write(file, bytes.copyOf(bytes.size / 2))

        val response = reload()

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        val body = response.json()
        assertEquals("secret_store_unreadable", body.text("error"))
        assertEquals("corrupt", body.text("problem"))
        assertFalse(response.bodyAsText().contains(file.toString()))
        assertEquals(listOf("a"), secrets().json().array("secrets").map { it.text("alias") })
      }

  @Test
  fun `a developer is refused and a request without a token is not let in`() = testApplication {
    engineWith(keystores.pkcs12("auth.p12", mapOf("a" to "value")))

    assertEquals(HttpStatusCode.Forbidden, secrets(TestTokens.ALICE).status)
    assertEquals(HttpStatusCode.Forbidden, reload(TestTokens.ALICE).status)
    assertEquals(HttpStatusCode.Unauthorized, secrets(null).status)
    assertEquals(HttpStatusCode.Unauthorized, reload(null).status)
  }
}
