package dev.lawlan.runline.engine.info

import dev.lawlan.runline.engine.support.PostgresTestContainer
import dev.lawlan.runline.engine.support.TestTokens
import dev.lawlan.runline.engine.support.configureEngine
import dev.lawlan.runline.engine.support.migratedDatabase
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.net.InetAddress
import java.time.Instant
import kotlin.test.*
import kotlinx.serialization.json.*

/**
 * `GET /api/v1/info` and `GET /api/v1/system` (WI-28, ADR-016) on the real application with a real
 * PostgreSQL.
 */
class InfoRoutesTest {
  private val build = BuildInfo.load()

  private suspend fun HttpResponse.json(): JsonObject =
      Json.parseToJsonElement(bodyAsText()).jsonObject

  private fun HttpRequestBuilder.bearer(token: String) =
      header(HttpHeaders.Authorization, "Bearer $token")

  @Test
  fun `info needs no token and holds the version, the hash and the dirty flag, nothing more`() =
      testApplication {
        configureEngine()

        val response = client.get("/api/v1/info")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.json()
        assertEquals(setOf("version", "commitHash", "dirty"), body.keys)
        assertEquals(build.version, body["version"]!!.jsonPrimitive.content)
        assertEquals(build.commitHash, body["commitHash"]!!.jsonPrimitive.content)
        assertEquals(build.dirty, body["dirty"]!!.jsonPrimitive.boolean)
      }

  @Test
  fun `info is not cached`() = testApplication {
    configureEngine()

    val response = client.get("/api/v1/info")

    assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
  }

  @Test
  fun `info still answers when the database is gone`() = testApplication {
    val database = migratedDatabase()
    configureEngine(database)
    startApplication()
    val name = database.url.substringAfterLast('/')
    PostgresTestContainer.connect(
            PostgresTestContainer.jdbcUrl,
            PostgresTestContainer.username,
            PostgresTestContainer.password,
        )
        .use { it.createStatement().use { s -> s.execute("DROP DATABASE $name WITH (FORCE)") } }

    val response = client.get("/api/v1/info")

    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals(build.commitHash, response.json()["commitHash"]!!.jsonPrimitive.content)
  }

  @Test
  fun `system refuses a request without a token and one with a token that is not valid`() =
      testApplication {
        configureEngine()
        val rest = createClient { expectSuccess = false }

        val anonymous = rest.get("/api/v1/system")
        val invalid = rest.get("/api/v1/system") { bearer("not-a-token") }

        assertEquals(HttpStatusCode.Unauthorized, anonymous.status)
        assertEquals(HttpStatusCode.Unauthorized, invalid.status)
      }

  @Test
  fun `system tells a developer the details and who the caller is`() = testApplication {
    configureEngine()

    val response = client.get("/api/v1/system") { bearer(TestTokens.ALICE) }

    assertEquals(HttpStatusCode.OK, response.status)
    val body = response.json()
    assertEquals(
        setOf(
            "version",
            "commitHash",
            "dirty",
            "buildTime",
            "jdk",
            "startedAt",
            "uptimeSeconds",
            "allowListVersion",
            "caller",
        ),
        body.keys,
    )
    assertEquals(build.version, body["version"]!!.jsonPrimitive.content)
    assertEquals(build.commitHash, body["commitHash"]!!.jsonPrimitive.content)
    assertEquals(build.dirty, body["dirty"]!!.jsonPrimitive.boolean)
    assertEquals(build.buildTime, Instant.parse(body["buildTime"]!!.jsonPrimitive.content))
    assertEquals(Runtime.version().toString(), body["jdk"]!!.jsonPrimitive.content)
    val startedAt = Instant.parse(body["startedAt"]!!.jsonPrimitive.content)
    assertTrue(startedAt <= Instant.now(), "$startedAt")
    assertTrue(body["uptimeSeconds"]!!.jsonPrimitive.long >= 0)
    assertEquals(
        setOf("name", "role"),
        body["caller"]!!.jsonObject.keys,
    )
    assertEquals("alice", body["caller"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    assertEquals("developer", body["caller"]!!.jsonObject["role"]!!.jsonPrimitive.content)
  }

  @Test
  fun `system tells an administrator the same, with the administrator role`() = testApplication {
    configureEngine()

    val body = client.get("/api/v1/system") { bearer(TestTokens.ROOT) }.json()

    assertEquals("root", body["caller"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    assertEquals("admin", body["caller"]!!.jsonObject["role"]!!.jsonPrimitive.content)
  }

  @Test
  fun `system names the allow list version in force`() = testApplication {
    configureEngine()

    val system = client.get("/api/v1/system") { bearer(TestTokens.ALICE) }.json()
    val allowList = client.get("/api/v1/allowlist") { bearer(TestTokens.ROOT) }.json()

    assertEquals(
        allowList["version"]!!.jsonPrimitive.content,
        system["allowListVersion"]!!.jsonPrimitive.content,
    )
  }

  @Test
  fun `system is not cached`() = testApplication {
    configureEngine()

    val response = client.get("/api/v1/system") { bearer(TestTokens.ALICE) }

    assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
  }

  @Test
  fun `neither answer holds a token, the database password, the host, a path or an environment value`() =
      testApplication {
        val database = migratedDatabase()
        configureEngine(database)

        val bodies =
            listOf(
                client.get("/api/v1/info").bodyAsText(),
                client.get("/api/v1/system") { bearer(TestTokens.ROOT) }.bodyAsText(),
            )

        val secrets =
            listOf(
                TestTokens.ALICE,
                TestTokens.BOB,
                TestTokens.ROOT,
                database.password,
                database.url,
                InetAddress.getLocalHost().hostName,
                System.getProperty("user.dir"),
                System.getProperty("user.name"),
            ) + System.getenv().values.filter { it.length > 8 }
        for (body in bodies) {
          for (secret in secrets) {
            assertFalse(body.contains(secret), "'$secret' appears in $body")
          }
        }
      }
}
