package dev.lawlan.runline.engine.resource

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
 * An `openai-compatible` resource through the whole Engine (WI-46): real PostgreSQL, a real PKCS12
 * keystore made by the JDK's keytool, the real API. What is stored, what is refused and why, what
 * the alias says about the keystore, and what is derived (the limit on the service's concurrency).
 */
class OpenAiResourceApiTest : ResourceApiSupport() {
  private val keystores = Keystores()
  private val passwordFile = keystores.passwordFile()
  private val marker = "sk-marker-0123456789abcdef"

  private fun JsonObject.obj(key: String) = this[key]!!.jsonObject

  private fun ApplicationTestBuilder.engineWith(keystore: Path?) =
      if (keystore == null) engine()
      else engine("secrets.keystorePath" to "$keystore", "secrets.passwordFile" to "$passwordFile")

  private suspend fun ApplicationTestBuilder.defineOpenAi(
      name: String,
      capacity: Int = 1,
      settings: String = """{"baseUrl":"http://127.0.0.1:9/v1"}""",
      alias: String? = null,
  ): HttpResponse =
      define(
          name,
          capacity,
          """"type":"openai-compatible","settings":$settings""" +
              (alias?.let { ""","secretAlias":"$it"""" } ?: ""),
      )

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

        val response = defineOpenAi("lemon", 2, """{"baseUrl":"http://127.0.0.1:9/v1/"}""")

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val created = response.json()
        assertEquals("openai-compatible", created.text("type"))
        val settings = created.obj("settings")
        assertEquals("http://127.0.0.1:9/v1", settings.text("baseUrl"))
        assertEquals(
            listOf(
                "chat.completions",
                "completions",
                "embeddings",
                "models.list",
                "models.retrieve",
            ),
            settings["endpoints"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(10_000, settings.obj("timeouts")["connectMs"]!!.jsonPrimitive.int)
        assertEquals(900_000, settings.obj("timeouts")["firstByteMs"]!!.jsonPrimitive.int)
        assertEquals(300_000, settings.obj("timeouts")["idleMs"]!!.jsonPrimitive.int)
        assertEquals(60_000, settings.obj("timeouts")["quotaWaitMs"]!!.jsonPrimitive.int)
        assertTrue("totalMs" !in settings.obj("timeouts"))
        assertEquals(1, settings["requestsPerRun"]!!.jsonPrimitive.int)
        assertEquals(32 * 1024 * 1024, settings["maxRequestBytes"]!!.jsonPrimitive.long)
        assertEquals(8 * 1024 * 1024, settings["maxResponseBytes"]!!.jsonPrimitive.long)
        assertEquals(JsonNull, created["secretAlias"])
        assertEquals("not_set", created.text("secretStatus"))
        assertEquals(2, created["concurrencyLimit"]!!.jsonPrimitive.int)
        assertEquals(0, created.obj("usage")["inFlightRequests"]!!.jsonPrimitive.int)
        assertEquals(created, resource("lemon"))
      }

  @Test
  fun `the limit on the service's concurrency is the capacity times the requests per run`() =
      testApplication {
        engine()
        defineOpenAi("lemon", 3, """{"baseUrl":"http://127.0.0.1:9/v1","requestsPerRun":2}""")

        assertEquals(6, resource("lemon")["concurrencyLimit"]!!.jsonPrimitive.int)
        assertEquals(
            2,
            change("lemon", """{"capacity":1}""").json()["concurrencyLimit"]!!.jsonPrimitive.int,
        )
        val listed = get("/api/v1/resources", TestTokens.ROOT).json().array("resources").single()
        assertEquals(2, listed["concurrencyLimit"]!!.jsonPrimitive.int)
      }

  @Test
  fun `other types have no limit of this kind and no usage`() = testApplication {
    engine()
    define("plain", 2)

    val plain = resource("plain")

    assertEquals(JsonNull, plain["concurrencyLimit"])
    assertEquals(JsonNull, plain["usage"])
    assertEquals("not_set", plain.text("secretStatus"))
  }

  @Test
  fun `a capacity change leaves the settings alone, for a file resource too`() = testApplication {
    engine("resources.root" to Files.createDirectories(dir.resolve("files")).toString())
    defineOpenAi("lemon", 1, """{"baseUrl":"http://127.0.0.1:9/v1","requestsPerRun":2}""")
    define("log", 1, """"type":"file","settings":{"path":"a/b.txt"}""")

    val lemon = change("lemon", """{"capacity":3}""")
    val log = change("log", """{"capacity":3}""")

    assertEquals(HttpStatusCode.OK, lemon.status, lemon.bodyAsText())
    assertEquals(2, lemon.json().obj("settings")["requestsPerRun"]!!.jsonPrimitive.int)
    assertEquals(HttpStatusCode.OK, log.status, log.bodyAsText())
    assertEquals("a/b.txt", log.json().obj("settings").text("path"))
  }

  @Test
  fun `each kind of refused setting is its own category of invalid_resource`() = testApplication {
    engine()
    val cases =
        mapOf(
            """{}""" to "invalid_settings",
            """{"baseUrl":"http://h/v1","path":"/x"}""" to "invalid_settings",
            """{"baseUrl":"ftp://h/v1"}""" to "invalid_base_url",
            """{"baseUrl":"http://user:pw@h/v1"}""" to "invalid_base_url",
            """{"baseUrl":"http://h/v1","headers":{"Authorization":"x"}}""" to "invalid_header",
            """{"baseUrl":"http://h/v1","headers":{"X-Api-Key":"x"}}""" to "invalid_header",
            """{"baseUrl":"http://h/v1","endpoints":["no.such"]}""" to "invalid_endpoint",
            """{"baseUrl":"http://h/v1","endpoints":["images.edits"]}""" to "invalid_endpoint",
            """{"baseUrl":"http://h/v1","defaults":{"messages":[]}}""" to
                "invalid_request_defaults",
            """{"baseUrl":"http://h/v1","timeouts":{"idleMs":0}}""" to "invalid_timeout",
            """{"baseUrl":"http://h/v1","requestsPerRun":0}""" to "invalid_limit",
            """{"baseUrl":"http://h/v1","maxResponseBytes":-1}""" to "invalid_limit",
        )

    for ((settings, problem) in cases) {
      val response = defineOpenAi("r-${problem}", 1, settings)

      assertEquals(HttpStatusCode.UnprocessableEntity, response.status, settings)
      assertEquals("invalid_resource", response.json().text("error"), settings)
      assertEquals(problem, response.json().text("problem"), settings)
      assertTrue(response.json().text("message").isNotBlank())
    }
    assertEquals(emptyList(), get("/api/v1/resources", TestTokens.ROOT).json().array("resources"))
    // Without any settings it is not a definition at all.
    val none = define("none", 1, """"type":"openai-compatible"""")
    assertEquals("invalid_settings", none.json().text("problem"))
  }

  @Test
  fun `a type that is not implemented yet is still refused as unsupported`() = testApplication {
    engine()

    val response = define("pool", 1, """"type":"jdbc-pool"""")

    assertEquals("unsupported_type", response.json().text("problem"))
  }

  @Test
  fun `an alias that is not a well formed name is refused and a good one is stored in lower case`() =
      testApplication {
        engine()

        for (bad in listOf("two words", "../x", "a/b", "", "-leading", "x".repeat(101), "ü")) {
          val response = defineOpenAi("r", 1, alias = bad)
          assertEquals(HttpStatusCode.UnprocessableEntity, response.status, bad)
          assertEquals("invalid_secret_alias", response.json().text("problem"), bad)
        }
        val created = defineOpenAi("lemon", 1, alias = "Lemon-Key.1")

        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        assertEquals("lemon-key.1", created.json().text("secretAlias"))
      }

  @Test
  fun `an alias is not_set, found, missing or invalid_secret as the keystore says, also after a reload`() =
      testApplication {
        val file =
            keystores.pkcs12(
                "status.p12",
                mapOf("lemon-key" to marker, "bad-key" to "café", "other" to "x"),
            )
        engineWith(file)
        defineOpenAi("found", alias = "LEMON-KEY")
        defineOpenAi("invalid", alias = "bad-key")
        defineOpenAi("missing", alias = "nobody")
        defineOpenAi("unset")

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
          keystores.deleteEntry(it, "lemon-key", passwordFile)
          keystores.importSecret(it, "nobody", "now-here", passwordFile)
        }
        assertEquals(
            HttpStatusCode.OK,
            client.post("/api/v1/secrets/reload") { bearer(TestTokens.ROOT)() }.status,
        )

        assertEquals(
            mapOf(
                "found" to "missing",
                "invalid" to "invalid_secret",
                "missing" to "found",
                "unset" to "not_set",
            ),
            statuses(),
        )
      }

  @Test
  fun `without a keystore every alias is missing`() = testApplication {
    engineWith(null)
    defineOpenAi("lemon", alias = "lemon-key")

    assertEquals("missing", resource("lemon").text("secretStatus"))
  }

  @Test
  fun `the secret listing and the reload name the resources that use an alias`() = testApplication {
    val file = keystores.pkcs12("users.p12", mapOf("lemon-key" to marker))
    engineWith(file)
    defineOpenAi("lemon", alias = "Lemon-Key")
    defineOpenAi("other", alias = "lemon-key")

    val listed = get("/api/v1/secrets", TestTokens.ROOT).json().array("secrets").single()
    replaceWith(file) {
      keystores.deleteEntry(it, "lemon-key", passwordFile)
      keystores.importSecret(it, "lemon-key", "sk-another-0123456789", passwordFile)
    }
    val reloaded = client.post("/api/v1/secrets/reload") { bearer(TestTokens.ROOT)() }.json()

    assertEquals(
        listOf("lemon", "other"),
        listed["usedBy"]!!.jsonArray.map { it.jsonPrimitive.content },
    )
    val changed = reloaded.array("changed").single()
    assertEquals("lemon-key", changed.text("alias"))
    assertEquals(
        listOf("lemon", "other"),
        changed["usedBy"]!!.jsonArray.map { it.jsonPrimitive.content },
    )
  }

  @Test
  fun `a change replaces the settings as a whole and changes the alias, and clears the last check`() =
      testApplication {
        engine()
        defineOpenAi("lemon", alias = "key-a")
        client.post("/api/v1/resources/lemon/check") { bearer(TestTokens.ROOT)() }
        assertTrue(resource("lemon")["lastCheck"] is JsonObject)

        val aliasOnly = change("lemon", """{"secretAlias":"KEY-B"}""")
        assertEquals(HttpStatusCode.OK, aliasOnly.status, aliasOnly.bodyAsText())
        assertEquals("key-b", aliasOnly.json().text("secretAlias"))
        assertEquals(JsonNull, aliasOnly.json()["lastCheck"])

        client.post("/api/v1/resources/lemon/check") { bearer(TestTokens.ROOT)() }
        assertTrue(resource("lemon")["lastCheck"] is JsonObject)
        val settings =
            change(
                "lemon",
                """{"settings":{"baseUrl":"http://127.0.0.1:10/v2","requestsPerRun":3}}""",
            )

        assertEquals(HttpStatusCode.OK, settings.status, settings.bodyAsText())
        val body = settings.json()
        assertEquals("http://127.0.0.1:10/v2", body.obj("settings").text("baseUrl"))
        assertEquals(3, body.obj("settings")["requestsPerRun"]!!.jsonPrimitive.int)
        assertEquals("key-b", body.text("secretAlias"), "an alias not named stays")
        assertEquals(JsonNull, body["lastCheck"])
        val refused = change("lemon", """{"settings":{"baseUrl":"ftp://x"}}""")
        assertEquals("invalid_base_url", refused.json().text("problem"))
        assertEquals("http://127.0.0.1:10/v2", resource("lemon").obj("settings").text("baseUrl"))
        val badAlias = change("lemon", """{"secretAlias":"no good"}""")
        assertEquals("invalid_secret_alias", badAlias.json().text("problem"))
      }

  @Test
  fun `the value of a secret is in no response`() = testApplication {
    val file = keystores.pkcs12("quiet.p12", mapOf("lemon-key" to marker))
    engineWith(file)
    defineOpenAi("lemon", alias = "lemon-key")
    val texts = buildList {
      add(get("/api/v1/resources", TestTokens.ROOT).bodyAsText())
      add(get("/api/v1/resources/lemon", TestTokens.ROOT).bodyAsText())
      add(get("/api/v1/secrets", TestTokens.ROOT).bodyAsText())
      add(client.post("/api/v1/resources/lemon/check") { bearer(TestTokens.ROOT)() }.bodyAsText())
      add(client.post("/api/v1/secrets/reload") { bearer(TestTokens.ROOT)() }.bodyAsText())
      add(get("/api/v1/resources/lemon", TestTokens.ROOT).bodyAsText())
    }

    assertTrue(texts.none { it.contains(marker) }, texts.toString())
  }

  @Test
  fun `a developer cannot see the settings of a resource`() = testApplication {
    engine()
    defineOpenAi("lemon")

    assertEquals(HttpStatusCode.Forbidden, get("/api/v1/resources/lemon", TestTokens.ALICE).status)
    assertEquals(HttpStatusCode.Forbidden, get("/api/v1/resources", TestTokens.ALICE).status)
  }
}
