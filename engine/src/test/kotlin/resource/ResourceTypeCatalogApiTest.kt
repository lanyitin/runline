package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.accessors.jdbc.PostgresProfile
import dev.lawlan.runline.accessors.openai.OpenAiEndpoints
import dev.lawlan.runline.engine.support.Keystores
import dev.lawlan.runline.engine.support.ResourceApiSupport
import dev.lawlan.runline.engine.support.TestTokens
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.nio.file.Files
import kotlin.test.*
import kotlinx.serialization.json.*

/**
 * `GET /api/v1/resource-types` through the whole Engine (WI-55, ADR-021): real PostgreSQL, the real
 * application. What the Engine tells of its closed set of types, to whom, and that what it tells is
 * what it accepts when a resource is created or changed.
 */
class ResourceTypeCatalogApiTest : ResourceApiSupport() {
  private suspend fun ApplicationTestBuilder.catalog(token: String? = TestTokens.ROOT) =
      get("/api/v1/resource-types", token)

  private suspend fun ApplicationTestBuilder.types(): List<JsonObject> {
    val response = catalog()
    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    return response.json().array("types")
  }

  @Test
  fun `an administrator gets the closed set of types in the Engine's order, a developer 403 and no token 401`() =
      testApplication {
        engine()

        assertEquals(
            listOf("counter", "file", "jdbc-pool", "openai-compatible"),
            types().map { it.text("type") },
        )
        assertEquals(HttpStatusCode.Forbidden, catalog(TestTokens.ALICE).status)
        assertEquals(HttpStatusCode.Unauthorized, catalog(null).status)
      }

  private suspend fun ApplicationTestBuilder.typeNamed(name: String) =
      types().single { it.text("type") == name }

  @Test
  fun `openai-compatible tells each entry of its catalog with what is fixed about it`() =
      testApplication {
        engine()

        val entries = typeNamed("openai-compatible").array("endpoints")

        assertEquals(28, entries.size)
        fun entry(id: String) = entries.single { it.text("id") == id }
        assertEquals(
            Json.parseToJsonElement(
                """{"id":"chat.completions","group":"chat","method":"POST","path":"/chat/completions",
                "request":"json","response":"json","streams":true,"defaultEnabled":true,"stateful":false}"""
            ),
            entry("chat.completions"),
        )
        assertEquals(
            Json.parseToJsonElement(
                """{"id":"files.delete","group":"files","method":"DELETE","path":"/files/{id}",
                "request":"none","response":"json","streams":false,"defaultEnabled":false,"stateful":true}"""
            ),
            entry("files.delete"),
        )
        assertEquals(
            Json.parseToJsonElement(
                """{"id":"audio.speech","group":"audio","method":"POST","path":"/audio/speech",
                "request":"json","response":"binary","streams":true,"defaultEnabled":false,"stateful":false}"""
            ),
            entry("audio.speech"),
        )
        assertEquals(
            Json.parseToJsonElement(
                """{"id":"files.create","group":"files","method":"POST","path":"/files",
                "request":"multipart","response":"json","streams":false,"defaultEnabled":false,"stateful":true}"""
            ),
            entry("files.create"),
        )
        // The service keeps a response it makes unless told not to: making one changes what it
        // keeps.
        assertEquals(
            Json.parseToJsonElement(
                """{"id":"responses.create","group":"responses","method":"POST","path":"/responses",
                "request":"json","response":"json","streams":true,"defaultEnabled":false,"stateful":true}"""
            ),
            entry("responses.create"),
        )
        assertEquals(
            listOf(
                "chat.completions",
                "completions",
                "embeddings",
                "models.list",
                "models.retrieve",
            ),
            entries.filter { it["defaultEnabled"]!!.jsonPrimitive.boolean }.map { it.text("id") },
        )
      }

  @Test
  fun `openai-compatible tells the request parameters a resource may default, lock or cap`() =
      testApplication {
        engine()

        val parameters = typeNamed("openai-compatible").array("requestParameters")

        assertEquals(
            listOf(
                "model:text:false",
                "temperature:number:true",
                "top_p:number:true",
                "top_k:number:true",
                "min_p:number:true",
                "max_tokens:number:true",
                "max_completion_tokens:number:true",
                "max_output_tokens:number:true",
                "stop:textOrList:false",
                "seed:number:true",
                "response_format:object:false",
                "presence_penalty:number:true",
                "frequency_penalty:number:true",
                "repeat_penalty:number:true",
                "n:number:true",
                "reasoning_effort:text:false",
            ),
            parameters.map { "${it.text("name")}:${it.text("kind")}:${it.text("ceiling")}" },
        )
      }

  @Test
  fun `jdbc-pool tells each database kind with the properties it allows and their rules, the others nothing`() =
      testApplication {
        engine()

        assertEquals(
            Json.parseToJsonElement(
                """{"type":"jdbc-pool","databases":[{"kind":"postgresql","properties":[
                  {"name":"ApplicationName","rule":"text","maxLength":64},
                  {"name":"currentSchema","rule":"pattern",
                   "pattern":"[A-Za-z_][A-Za-z0-9_$]{0,62}(?:,[A-Za-z_][A-Za-z0-9_$]{0,62}){0,7}"},
                  {"name":"tcpKeepAlive","rule":"oneOf","values":["true","false"]}]}]}"""
            ),
            typeNamed("jdbc-pool"),
        )
        assertEquals(buildJsonObject { put("type", "counter") }, typeNamed("counter"))
        assertEquals(buildJsonObject { put("type", "file") }, typeNamed("file"))
      }

  @Test
  fun `nothing of a resource, a secret or the Engine's configuration is told`() = testApplication {
    val keystores = Keystores()
    val marker = "sk-wi55-marker-7f3a9c0d2e"
    val keystore = keystores.pkcs12("store.p12", mapOf("wi55-unique-alias" to marker))
    val root = Files.createDirectories(dir.resolve("wi55-unique-root"))
    engine(
        "secrets.keystorePath" to "$keystore",
        "secrets.passwordFile" to "${keystores.passwordFile()}",
        "resources.root" to "$root",
    )
    assertEquals(
        HttpStatusCode.Created,
        define(
                "wi55-unique-resource",
                3,
                """"type":"openai-compatible","secretAlias":"wi55-unique-alias",
                "settings":{"baseUrl":"http://wi55-unique-host.invalid/v1","defaults":{"model":"wi55-unique-model"}}""",
            )
            .status,
    )
    val told = catalog().bodyAsText()
    val distinctive =
        listOf(
            marker,
            "wi55-unique-alias",
            "wi55-unique-resource",
            "wi55-unique-host",
            "wi55-unique-model",
            "$root",
            "wi55-unique-root",
            "$keystore",
            Keystores.DEFAULT_PASSWORD,
            ops,
        )

    for (text in distinctive) assertFalse(text in told, "the catalog says $text")
    // The check on the check: the resource and its alias are really there to be told.
    val resources = get("/api/v1/resources", TestTokens.ROOT).bodyAsText()
    assertTrue("wi55-unique-alias" in resources && "wi55-unique-host" in resources)
    assertFalse(marker in resources)
  }

  /** The problem of a 422, or null for a 201 or 200; anything else fails the test. */
  private suspend fun HttpResponse.problem(): String? =
      when (status) {
        HttpStatusCode.Created,
        HttpStatusCode.OK -> null
        HttpStatusCode.UnprocessableEntity -> json().text("problem")
        else -> fail("$status ${bodyAsText()}")
      }

  private var count = 0

  private suspend fun ApplicationTestBuilder.openAi(settings: String) =
      define("o${count++}", 1, """"type":"openai-compatible","settings":$settings""")

  private suspend fun ApplicationTestBuilder.pool(settings: String) =
      define("p${count++}", 1, """"type":"jdbc-pool","settings":$settings""")

  /** Every name the catalog of this Engine has, told or not, and names that are no entry at all. */
  private val candidateEntries =
      OpenAiEndpoints.all.map { it.id } +
          listOf("root", "CHAT.COMPLETIONS", "chat/completions", "files", "realtime", "")

  @Test
  fun `an entry told is accepted when a resource is created or changed, and a name not told is invalid_endpoint`() =
      testApplication {
        engine()
        val told = typeNamed("openai-compatible").array("endpoints").map { it.text("id") }
        assertEquals(HttpStatusCode.Created, openAi("""{"baseUrl":"http://h/v1"}""").status)
        val existing = "o${count - 1}"

        for (name in candidateEntries) {
          val settings = """{"baseUrl":"http://h/v1","endpoints":["$name"]}"""
          val expected = if (name in told) null else "invalid_endpoint"
          assertEquals(expected, openAi(settings).problem(), "create with $name")
          assertEquals(
              expected,
              change(existing, """{"settings":$settings}""").problem(),
              "change to $name",
          )
        }
        // All of them at once, as an administrator who enables everything would.
        val all = told.joinToString(",") { "\"$it\"" }
        val created = openAi("""{"baseUrl":"http://h/v1","endpoints":[$all]}""")
        assertEquals(
            told,
            created.json()["settings"]!!.jsonObject["endpoints"]!!.jsonArray.map {
              it.jsonPrimitive.content
            },
        )
      }

  @Test
  fun `the entries told as enabled by default are those written when endpoints are left out`() =
      testApplication {
        engine()
        val byDefault =
            typeNamed("openai-compatible")
                .array("endpoints")
                .filter { it["defaultEnabled"]!!.jsonPrimitive.boolean }
                .map { it.text("id") }

        val created = openAi("""{"baseUrl":"http://h/v1"}""")

        assertEquals(HttpStatusCode.Created, created.status)
        assertEquals(
            byDefault,
            created.json()["settings"]!!.jsonObject["endpoints"]!!.jsonArray.map {
              it.jsonPrimitive.content
            },
        )
      }

  private fun database(kind: String, properties: String = "") =
      """{"kind":"$kind","host":"db.internal","database":"app","username":"app_rw"$properties}"""

  @Test
  fun `a database kind told is accepted and one not told is unsupported_database`() =
      testApplication {
        engine()
        val told = typeNamed("jdbc-pool").array("databases").map { it.text("kind") }
        // (An empty kind is not a kind at all: invalid_settings, as it always was.)
        assertEquals(HttpStatusCode.Created, pool(database(told.first())).status)
        val existing = "p${count - 1}"

        for (kind in told + listOf("PostgreSQL", "postgres", "mysql", "oracle", "sqlite")) {
          val expected = if (kind in told) null else "unsupported_database"
          assertEquals(expected, pool(database(kind)).problem(), "create with $kind")
          assertEquals(
              expected,
              change(existing, """{"settings":${database(kind)}}""").problem(),
              "change to $kind",
          )
        }
      }

  /** A value the rule told accepts, and one it does not. */
  private fun samplesOf(property: JsonObject): Pair<String, String> =
      when (property.text("rule")) {
        "text" -> {
          val most = property["maxLength"]!!.jsonPrimitive.int
          "a".repeat(most) to "a".repeat(most + 1)
        }
        "oneOf" -> property["values"]!!.jsonArray.first().jsonPrimitive.content to "not-one-of-them"
        "pattern" -> {
          val pattern = Regex(property.text("pattern"))
          val good = listOf("public", "a", "true", "1").first { pattern.matches(it) }
          val bad = listOf("1 2", "", "\u0001", "a;b").first { !pattern.matches(it) }
          good to bad
        }
        else -> fail("a rule the test does not know: $property")
      }

  @Test
  fun `a property told is accepted with a value its rule allows, refused with another, and one not told is property_not_allowed`() =
      testApplication {
        engine()
        val databases = typeNamed("jdbc-pool").array("databases")
        // Every property the profiles of this Engine have, told or not, and driver properties
        // that are none of them.
        val notTold =
            PostgresProfile.allowedProperties.keys +
                listOf(
                    "sslmode",
                    "sslfactory",
                    "socketFactory",
                    "user",
                    "password",
                    "options",
                    "loggerFile",
                    "applicationname",
                    "readOnly",
                )

        for (db in databases) {
          val kind = db.text("kind")
          assertEquals(HttpStatusCode.Created, pool(database(kind)).status)
          val existing = "p${count - 1}"
          val told = db.array("properties")
          for (property in told) {
            val name = property.text("name")
            val (good, bad) = samplesOf(property)
            val accepted = database(kind, ""","properties":{"$name":"$good"}""")
            val refused = database(kind, ""","properties":{"$name":"$bad"}""")
            assertNull(pool(accepted).problem(), "$name=$good")
            assertNull(change(existing, """{"settings":$accepted}""").problem(), "$name=$good")
            assertEquals("invalid_settings", pool(refused).problem(), "$name=$bad")
          }
          val names = told.map { it.text("name") }
          for (name in notTold.filterNot { it in names }) {
            val settings = database(kind, ""","properties":{"$name":"x"}""")
            assertEquals("property_not_allowed", pool(settings).problem(), name)
            assertEquals(
                "property_not_allowed",
                change(existing, """{"settings":$settings}""").problem(),
                name,
            )
          }
        }
      }
}
