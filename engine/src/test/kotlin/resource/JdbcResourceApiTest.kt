package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.accessors.jdbc.RealPostgres
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
import kotlin.test.*
import kotlinx.serialization.json.*

/**
 * A `jdbc-pool` resource through the whole Engine (WI-48): real PostgreSQL for the Engine and for
 * the database the resource points at, a real PKCS12 keystore made by the JDK's keytool, the real
 * API. What is stored, what is refused and why, what the alias says, and what is derived.
 */
class JdbcResourceApiTest : ResourceApiSupport() {
  private val keystores = Keystores()
  private val passwordFile = keystores.passwordFile()
  private val marker = "pw-marker-0123456789abcdef"

  private fun JsonObject.obj(key: String) = this[key]!!.jsonObject

  private fun settings(extra: String = "", host: String = "db.internal") =
      """{"kind":"postgresql","host":"$host","database":"app","username":"app_rw"${if (extra.isEmpty()) "" else ",$extra"}}"""

  private suspend fun ApplicationTestBuilder.defineJdbc(
      name: String,
      capacity: Int = 1,
      settings: String = settings(),
      alias: String? = null,
  ): HttpResponse =
      define(
          name,
          capacity,
          """"type":"jdbc-pool","settings":$settings""" +
              (alias?.let { ""","secretAlias":"$it"""" } ?: ""),
      )

  private fun ApplicationTestBuilder.engineWith(keystore: Path?) =
      if (keystore == null) engine()
      else engine("secrets.keystorePath" to "$keystore", "secrets.passwordFile" to "$passwordFile")

  private fun replaceWith(file: Path, change: (Path) -> Unit) {
    val copy = file.resolveSibling(file.fileName.toString() + ".new")
    Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING)
    change(copy)
    Files.move(copy, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
  }

  @Test
  fun `the resource is created with every effective setting written out and what is derived from it`() =
      testApplication {
        engine()

        val response = defineJdbc("db", 3, settings(""""connectionsPerRun":2"""))

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val created = response.json()
        assertEquals("jdbc-pool", created.text("type"))
        val stored = created.obj("settings")
        assertEquals("postgresql", stored.text("kind"))
        assertEquals(5432, stored["port"]!!.jsonPrimitive.int)
        assertEquals(2, stored["connectionsPerRun"]!!.jsonPrimitive.int)
        assertEquals(10_000, stored.obj("timeouts")["connectMs"]!!.jsonPrimitive.int)
        assertEquals(300_000, stored.obj("timeouts")["statementMs"]!!.jsonPrimitive.int)
        assertEquals(60_000, stored.obj("timeouts")["quotaWaitMs"]!!.jsonPrimitive.int)
        assertEquals(10_000, stored["maxRows"]!!.jsonPrimitive.int)
        assertEquals(JsonNull, created["secretAlias"])
        assertEquals("not_set", created.text("secretStatus"))
        assertEquals(6, created["concurrencyLimit"]!!.jsonPrimitive.int)
        assertEquals(0, created.obj("usage")["activeConnections"]!!.jsonPrimitive.int)
        assertTrue("inFlightRequests" !in created.obj("usage"))
        assertEquals(created, resource("db"))
      }

  @Test
  fun `a setting that is not valid is refused saying which kind of fault, and nothing is stored`() =
      testApplication {
        engine()
        val cases =
            listOf(
                """"settings":{}""" to "invalid_settings",
                """"settings":{"kind":"oracle","host":"h","database":"d","username":"u"}""" to
                    "unsupported_database",
                """"settings":${settings(""""properties":{"socketFactory":"org.evil.Factory"}""")}""" to
                    "property_not_allowed",
                """"settings":${settings(""""properties":{"sslmode":"disable"}""")}""" to
                    "property_not_allowed",
                """"settings":${settings(""""properties":{"loggerFile":"/tmp/x"}""")}""" to
                    "property_not_allowed",
                """"settings":${settings(""""url":"jdbc:postgresql://evil/db"""")}""" to
                    "invalid_settings",
                """"settings":${settings(""""password":"x"""")}""" to "invalid_settings",
                """"settings":${settings(host = "db.internal/other?ssl=false")}""" to
                    "invalid_settings",
                """"settings":${settings(""""connectionsPerRun":0""")}""" to "invalid_limit",
                """"settings":${settings(""""timeouts":{"statementMs":0}""")}""" to
                    "invalid_timeout",
                """"settings":${settings()},"secretAlias":"no good"""" to "invalid_secret_alias",
            )

        for ((body, problem) in cases) {
          val response = define("bad", 1, """"type":"jdbc-pool",$body""")

          assertEquals(HttpStatusCode.UnprocessableEntity, response.status, body)
          assertEquals("invalid_resource", response.json().text("error"), body)
          assertEquals(problem, response.json().text("problem"), body)
        }
        assertEquals(
            emptyList(),
            get("/api/v1/resources", TestTokens.ROOT).json().array("resources"),
        )
      }

  @Test
  fun `a change replaces the settings as a whole, and a refused one changes nothing`() =
      testApplication {
        engine()
        defineJdbc("db", alias = "pw-a")
        client.post("/api/v1/resources/db/check") { bearer(TestTokens.ROOT)() }
        assertTrue(resource("db")["lastCheck"] is JsonObject)

        val changed = change("db", """{"settings":${settings(host = "db2.internal")}}""")

        assertEquals(HttpStatusCode.OK, changed.status, changed.bodyAsText())
        assertEquals("db2.internal", changed.json().obj("settings").text("host"))
        assertEquals("pw-a", changed.json().text("secretAlias"))
        assertEquals(JsonNull, changed.json()["lastCheck"])
        val refused =
            change("db", """{"settings":${settings(""""properties":{"socketFactory":"x"}""")}}""")
        assertEquals("property_not_allowed", refused.json().text("problem"))
        assertEquals("db2.internal", resource("db").obj("settings").text("host"))
      }

  @Test
  fun `an alias is not_set, found, missing or invalid_secret as the keystore says, also after a reload`() =
      testApplication {
        val file = keystores.pkcs12("jdbc-status.p12", mapOf("db-pw" to marker, "bad-pw" to "café"))
        engineWith(file)
        defineJdbc("found", alias = "DB-PW")
        defineJdbc("invalid", alias = "bad-pw")
        defineJdbc("missing", alias = "nobody")
        defineJdbc("unset")

        suspend fun statuses() =
            get("/api/v1/resources", TestTokens.ROOT).json().array("resources").associate {
              it.text("name") to it.text("secretStatus")
            }

        assertEquals(
            mapOf(
                "found" to "found",
                "invalid" to "invalid_secret",
                "missing" to "missing",
                "unset" to "not_set",
            ),
            statuses(),
        )
        replaceWith(file) {
          keystores.deleteEntry(it, "db-pw", passwordFile)
          keystores.importSecret(it, "nobody", "now-here", passwordFile)
        }
        client.post("/api/v1/secrets/reload") { bearer(TestTokens.ROOT)() }

        assertEquals("missing", statuses().getValue("found"))
        assertEquals("found", statuses().getValue("missing"))
      }

  @Test
  fun `the secret listing and the reload name the jdbc-pool resources that use an alias`() =
      testApplication {
        val file = keystores.pkcs12("jdbc-users.p12", mapOf("db-pw" to marker))
        engineWith(file)
        defineJdbc("one", alias = "Db-Pw")
        defineJdbc("two", alias = "db-pw")

        val listed = get("/api/v1/secrets", TestTokens.ROOT).json().array("secrets").single()
        replaceWith(file) {
          keystores.deleteEntry(it, "db-pw", passwordFile)
          keystores.importSecret(it, "db-pw", "another-password-1", passwordFile)
        }
        val reloaded = client.post("/api/v1/secrets/reload") { bearer(TestTokens.ROOT)() }.json()

        assertEquals(
            listOf("one", "two"),
            listed["usedBy"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("one", "two"),
            reloaded.array("changed").single()["usedBy"]!!.jsonArray.map {
              it.jsonPrimitive.content
            },
        )
      }

  @Test
  fun `the value of a password is in no response`() = testApplication {
    val file = keystores.pkcs12("jdbc-quiet.p12", mapOf("db-pw" to marker))
    engineWith(file)
    defineJdbc("db", alias = "db-pw")
    val texts = buildList {
      add(get("/api/v1/resources", TestTokens.ROOT).bodyAsText())
      add(get("/api/v1/resources/db", TestTokens.ROOT).bodyAsText())
      add(get("/api/v1/secrets", TestTokens.ROOT).bodyAsText())
      add(client.post("/api/v1/resources/db/check") { bearer(TestTokens.ROOT)() }.bodyAsText())
      add(client.post("/api/v1/secrets/reload") { bearer(TestTokens.ROOT)() }.bodyAsText())
      add(get("/api/v1/resources/db", TestTokens.ROOT).bodyAsText())
    }

    assertTrue(texts.none { it.contains(marker) }, texts.toString())
  }

  @Test
  fun `a developer cannot see the settings of a resource`() = testApplication {
    engine()
    defineJdbc("db")

    val response = get("/api/v1/resources/db", TestTokens.ALICE)

    assertEquals(HttpStatusCode.Forbidden, response.status)
    assertFalse(response.bodyAsText().contains("db.internal"))
  }

  @Test
  fun `the type of a resource that exists cannot be changed to or from jdbc-pool`() =
      testApplication {
        engine()
        define("c")
        defineJdbc("db")

        assertEquals(
            "immutable_type",
            change("c", """{"type":"jdbc-pool"}""").json().text("problem"),
        )
        assertEquals(
            "immutable_type",
            change("db", """{"type":"counter"}""").json().text("problem"),
        )
        assertEquals(RealPostgres.host.isNotBlank(), true)
      }
}
