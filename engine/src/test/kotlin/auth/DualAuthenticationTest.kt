package dev.lawlan.runline.engine.auth

import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.oidc.NewSession
import dev.lawlan.runline.engine.oidc.PostgresSessionStore
import dev.lawlan.runline.engine.oidc.SessionIds
import dev.lawlan.runline.engine.oidc.SignInIdentity
import dev.lawlan.runline.engine.support.PipelineJars
import dev.lawlan.runline.engine.support.TestTokens
import dev.lawlan.runline.engine.support.configureEngine
import dev.lawlan.runline.engine.support.migratedDatabase
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.test.*
import kotlinx.serialization.json.*

/**
 * Bearer tokens and sessions side by side (WI-1201, ADR-029 decision 5): who is asking is decided
 * the same way for both, a session's changes need its CSRF token and the Engine's own origin, and a
 * token needs neither. Real PostgreSQL, real HTTP requests; the sessions are rows made the way a
 * sign-in makes them.
 */
class DualAuthenticationTest {
  private val database = migratedDatabase()
  private val dir = Files.createTempDirectory("dual-auth")
  private val base = "https://runline.example.test"

  private class Session(val id: String, val csrf: String)

  private fun session(name: String, role: Role, expiresIn: Duration = Duration.ofDays(1)): Session {
    val id = SessionIds.newToken()
    val csrf = SessionIds.newToken()
    val now = Instant.now()
    PostgresSessionStore(dataSourceOf(database))
        .signIn(
            SignInIdentity("https://auth.example.test/", "sub-$name", name, "Display $name"),
            NewSession(SessionIds.hash(id), role, csrf, byteArrayOf(1), now, now + expiresIn),
            now,
        )
    return Session(id, csrf)
  }

  private fun ApplicationTestBuilder.engine() {
    configureEngine(
        database,
        mapOf(
            "oidc.issuer" to "https://auth.example.test/application/o/runline/",
            "oidc.clientId" to "runline",
            "oidc.clientSecret" to "client-secret-value",
            "oidc.publicBaseUrl" to base,
            "session.encryptionKey" to "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
            // The sessions are rows made by the test, not by a sign-in the provider knows.
            "session.revalidateSeconds" to "86400",
        ),
    )
  }

  private fun HttpRequestBuilder.cookie(s: Session) =
      header(HttpHeaders.Cookie, "runline_session=${s.id}")

  private fun HttpRequestBuilder.bearer(token: String) =
      header(HttpHeaders.Authorization, "Bearer $token")

  /** What a browser of the Console sends with a change: the cookie, the token and the origin. */
  private fun HttpRequestBuilder.asBrowser(
      s: Session,
      origin: String? = base,
      csrf: String? = s.csrf,
  ) {
    cookie(s)
    csrf?.let { header("X-CSRF-Token", it) }
    origin?.let { header(HttpHeaders.Origin, it) }
  }

  private fun jar(name: String = "p"): ByteArray =
      Files.readAllBytes(
          PipelineJars.build(
              dir,
              "j-${System.nanoTime()}.jar",
              mapOf("demo.Pipe" to PipelineJars.pipeline("demo.Pipe", name)),
          )
      )

  private fun artifacts(): Long =
      dataSourceOf(database).connection.use { c ->
        c.createStatement().use { s ->
          s.executeQuery("SELECT count(*) FROM pipeline_artifact").use {
            it.next()
            it.getLong(1)
          }
        }
      }

  private suspend fun HttpResponse.json() = Json.parseToJsonElement(bodyAsText()).jsonObject

  // ---- who is asking ----

  @Test
  fun `a session is who it says, and the report says it came from a session`() = testApplication {
    engine()
    val s = session("oidc-dev", Role.DEVELOPER)

    val response = client.get("/api/v1/system") { cookie(s) }

    assertEquals(HttpStatusCode.OK, response.status)
    val caller = response.json().getValue("caller").jsonObject
    assertEquals("oidc-dev", caller.getValue("name").jsonPrimitive.content)
    assertEquals("developer", caller.getValue("role").jsonPrimitive.content)
    assertEquals("oidc", caller.getValue("source").jsonPrimitive.content)
    val text = response.bodyAsText()
    assertFalse(text.contains(s.id) || text.contains(s.csrf), "a credential is in the report")
  }

  @Test
  fun `a token is who it says, and the report says it came from a token`() = testApplication {
    engine()

    val response = client.get("/api/v1/system") { bearer(TestTokens.ALICE) }

    val caller = response.json().getValue("caller").jsonObject
    assertEquals("alice", caller.getValue("name").jsonPrimitive.content)
    assertEquals("token", caller.getValue("source").jsonPrimitive.content)
    assertFalse(response.bodyAsText().contains(TestTokens.ALICE))
  }

  @Test
  fun `the Authorization header decides alone, a good cookie does not make up for a bad token`() =
      testApplication {
        engine()
        val s = session("oidc-dev", Role.DEVELOPER)

        val badToken =
            client.get("/api/v1/system") {
              bearer("not-a-token")
              cookie(s)
            }
        assertEquals(HttpStatusCode.Unauthorized, badToken.status)
        assertTrue(
            badToken.headers[HttpHeaders.WWWAuthenticate]!!.startsWith("Bearer realm=\"runline\"")
        )

        val otherScheme =
            client.get("/api/v1/system") {
              header(HttpHeaders.Authorization, "Basic YWxpY2U6eA==")
              cookie(s)
            }
        assertEquals(HttpStatusCode.Unauthorized, otherScheme.status)
      }

  @Test
  fun `no cookie, an unknown one or an expired session is refused as a missing token is`() =
      testApplication {
        engine()
        val expired = session("oidc-old", Role.DEVELOPER, expiresIn = Duration.ofSeconds(-5))

        for (request in
            listOf<HttpRequestBuilder.() -> Unit>(
                {},
                { header(HttpHeaders.Cookie, "runline_session=made-up") },
                { cookie(expired) },
            )) {
          val response = client.get("/api/v1/system", request)
          assertEquals(HttpStatusCode.Unauthorized, response.status)
          assertEquals("Bearer realm=\"runline\"", response.headers[HttpHeaders.WWWAuthenticate])
          assertEquals("", response.bodyAsText())
        }
      }

  @Test
  fun `the role of a session is judged as the role of a token`() = testApplication {
    engine()
    val developer = session("oidc-dev", Role.DEVELOPER)
    val admin = session("oidc-admin", Role.ADMIN)

    assertEquals(
        HttpStatusCode.Forbidden,
        client.get("/api/v1/allowlist") { cookie(developer) }.status,
    )
    assertEquals(HttpStatusCode.OK, client.get("/api/v1/allowlist") { cookie(admin) }.status)
    assertEquals(
        HttpStatusCode.Forbidden,
        client.get("/api/v1/allowlist") { bearer(TestTokens.ALICE) }.status,
    )
  }

  // ---- what each can see (ADR-020) ----

  @Test
  fun `people of the two sources do not see each other's versions, which are as if absent`() =
      testApplication {
        engine()
        val person = session("oidc-dev", Role.DEVELOPER)
        val admin = session("oidc-admin", Role.ADMIN)
        val theirs = jar("theirs")
        val tokens = jar("tokens")
        val uploadedByPerson =
            client
                .post("/api/v1/artifacts") {
                  asBrowser(person)
                  setBody(theirs)
                }
                .json()
                .getValue("contentHash")
                .jsonPrimitive
                .content
        val uploadedByToken =
            client
                .post("/api/v1/artifacts") {
                  bearer(TestTokens.ALICE)
                  setBody(tokens)
                }
                .json()
                .getValue("contentHash")
                .jsonPrimitive
                .content
        val unknown = "c".repeat(64)

        val absent = client.get("/api/v1/artifacts/$unknown") { cookie(person) }
        val hidden = client.get("/api/v1/artifacts/$uploadedByToken") { cookie(person) }
        assertEquals(HttpStatusCode.NotFound, hidden.status)
        assertEquals(absent.bodyAsText(), hidden.bodyAsText())
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/v1/artifacts/$uploadedByPerson") { bearer(TestTokens.ALICE) }.status,
        )
        assertEquals(
            HttpStatusCode.OK,
            client.get("/api/v1/artifacts/$uploadedByPerson") { cookie(person) }.status,
        )
        // The uploader on record is the name of the person, as it would be the name of a token.
        val mine = client.get("/api/v1/artifacts/$uploadedByPerson") { cookie(person) }.json()
        assertEquals("oidc-dev", mine.getValue("uploader").jsonPrimitive.content)
        // An administrator sees both, by either source.
        assertEquals(
            HttpStatusCode.OK,
            client.get("/api/v1/artifacts/$uploadedByToken") { cookie(admin) }.status,
        )
        assertEquals(
            HttpStatusCode.OK,
            client.get("/api/v1/artifacts/$uploadedByPerson") { bearer(TestTokens.ROOT) }.status,
        )
      }

  // ---- changes need the CSRF token and the origin ----

  @Test
  fun `a change by a session without its CSRF token, or with another, is refused and does nothing`() =
      testApplication {
        engine()
        val s = session("oidc-dev", Role.DEVELOPER)
        val other = session("oidc-other", Role.DEVELOPER)

        for (csrf in listOf<String?>(null, "", "wrong", other.csrf)) {
          val response =
              client.post("/api/v1/artifacts") {
                asBrowser(s, csrf = csrf)
                setBody(jar())
              }
          assertEquals(HttpStatusCode.Forbidden, response.status, "token: $csrf")
          assertEquals("csrf_invalid", response.json().getValue("error").jsonPrimitive.content)
        }
        assertEquals(0, artifacts())
      }

  @Test
  fun `a change by a session from another origin, or from none, is refused and does nothing`() =
      testApplication {
        engine()
        val s = session("oidc-dev", Role.DEVELOPER)

        for (origin in
            listOf<String?>(
                null,
                "https://evil.example",
                "http://runline.example.test",
                "https://runline.example.test:8443",
                "https://runline.example.test.evil.example",
                "null",
            )) {
          val response =
              client.post("/api/v1/artifacts") {
                asBrowser(s, origin = origin)
                setBody(jar())
              }
          assertEquals(HttpStatusCode.Forbidden, response.status, "origin: $origin")
          assertEquals("origin_invalid", response.json().getValue("error").jsonPrimitive.content)
        }
        assertEquals(0, artifacts())
      }

  @Test
  fun `a page of another site cannot change anything with the cookie it is sent along with`() =
      testApplication {
        engine()
        val s = session("oidc-dev", Role.DEVELOPER)

        // The attacker's page cannot read the token, so it sends the cookie and its own origin.
        val forged =
            client.post("/api/v1/artifacts") {
              cookie(s)
              header(HttpHeaders.Origin, "https://evil.example")
              setBody(jar())
            }

        assertEquals(HttpStatusCode.Forbidden, forged.status)
        assertEquals(0, artifacts())
      }

  @Test
  fun `a change with the token and the origin is made, whichever the method`() = testApplication {
    engine()
    val admin = session("oidc-admin", Role.ADMIN)

    val uploaded =
        client.post("/api/v1/artifacts") {
          asBrowser(admin)
          setBody(jar())
        }
    assertEquals(HttpStatusCode.Created, uploaded.status)
    val hash = uploaded.json().getValue("contentHash").jsonPrimitive.content
    assertEquals(1, artifacts())

    val deleted = client.delete("/api/v1/artifacts/$hash") { asBrowser(admin) }
    assertEquals(HttpStatusCode.NoContent, deleted.status)
    assertEquals(0, artifacts())
  }

  @Test
  fun `a token needs neither CSRF token nor origin, and a read by a session needs neither`() =
      testApplication {
        engine()
        val s = session("oidc-dev", Role.DEVELOPER)

        val byToken =
            client.post("/api/v1/artifacts") {
              bearer(TestTokens.ALICE)
              header(HttpHeaders.Origin, "https://evil.example")
              setBody(jar())
            }
        assertEquals(HttpStatusCode.Created, byToken.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/definitions") { cookie(s) }.status)
      }

  @Test
  fun `the origin is compared as an origin, case and default port do not matter`() =
      testApplication {
        engine()
        val s = session("oidc-dev", Role.DEVELOPER)

        for (origin in listOf("https://RUNLINE.example.test", "https://runline.example.test:443")) {
          val response =
              client.post("/api/v1/artifacts") {
                asBrowser(s, origin = origin)
                setBody(jar(name = "p-${System.nanoTime()}"))
              }
          assertEquals(HttpStatusCode.Created, response.status, origin)
        }
      }

  // ---- logging out is a change ----

  @Test
  fun `signing out needs the token and the origin, and without them the session stays`() =
      testApplication {
        engine()
        val s = session("oidc-dev", Role.DEVELOPER)

        val noToken = client.post("/api/v1/auth/logout") { asBrowser(s, csrf = null) }
        val evil =
            client.post("/api/v1/auth/logout") { asBrowser(s, origin = "https://evil.example") }

        assertEquals(HttpStatusCode.Forbidden, noToken.status)
        assertEquals(HttpStatusCode.Forbidden, evil.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/auth/session") { cookie(s) }.status)

        val out = client.post("/api/v1/auth/logout") { asBrowser(s) }
        assertEquals(HttpStatusCode.NoContent, out.status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/api/v1/auth/session") { cookie(s) }.status,
        )
        // With nothing to end there is nothing to protect, and it is no failure.
        assertEquals(HttpStatusCode.NoContent, client.post("/api/v1/auth/logout").status)
        assertEquals(
            HttpStatusCode.NoContent,
            client.post("/api/v1/auth/logout") { cookie(s) }.status,
        )
      }

  // ---- what a session does not open ----

  @Test
  fun `the webhook entry keeps its own authentication, a session is nothing to it`() =
      testApplication {
        engine()
        val s = session("oidc-dev", Role.DEVELOPER)

        val response =
            client.post("/api/v1/webhooks/x") {
              asBrowser(s)
              header("X-Runline-Delivery-Id", "d1")
            }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains("unauthorized"))
      }
}
