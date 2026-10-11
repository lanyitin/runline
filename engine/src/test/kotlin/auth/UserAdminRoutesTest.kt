package dev.lawlan.runline.engine.auth

import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.oidc.NewSession
import dev.lawlan.runline.engine.oidc.PostgresSessionStore
import dev.lawlan.runline.engine.oidc.SessionIds
import dev.lawlan.runline.engine.oidc.SignInIdentity
import dev.lawlan.runline.engine.support.TestTokens
import dev.lawlan.runline.engine.support.configureEngine
import dev.lawlan.runline.engine.support.migratedDatabase
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.time.Duration
import java.time.Instant
import kotlin.test.*
import kotlinx.serialization.json.*

/**
 * The administration of people and sessions over HTTP (WI-1205): only administrators, by token or
 * by session (with the CSRF rules of every change), nothing about who exists is told to anybody
 * else, and an administrator can lock themselves out of the session but not of the tokens.
 */
class UserAdminRoutesTest {
  private val database = migratedDatabase()
  private val base = "https://runline.example.test"

  private class Session(val id: String, val csrf: String)

  private fun session(name: String, role: Role): Session {
    val id = SessionIds.newToken()
    val csrf = SessionIds.newToken()
    val now = Instant.now()
    PostgresSessionStore(dataSourceOf(database))
        .signIn(
            SignInIdentity("https://auth.example.test/", "sub-$name", name, "Display $name"),
            NewSession(
                SessionIds.hash(id),
                role,
                csrf,
                byteArrayOf(1),
                now,
                now + Duration.ofDays(1),
            ),
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

  private fun HttpRequestBuilder.browser(s: Session) {
    cookie(s)
    header("X-CSRF-Token", s.csrf)
    header(HttpHeaders.Origin, base)
  }

  private fun HttpRequestBuilder.token(t: String) = header(HttpHeaders.Authorization, "Bearer $t")

  private suspend fun HttpResponse.json() = Json.parseToJsonElement(bodyAsText())

  @Test
  fun `a developer may do none of it, by token or session, and cannot tell who exists`() =
      testApplication {
        engine()
        val dev = session("oidc-dev", Role.DEVELOPER)
        session("ann", Role.DEVELOPER)

        for (name in listOf("ann", "ghost")) {
          val asks: List<suspend () -> HttpResponse> =
              listOf(
                  { client.get("/api/v1/users") { token(TestTokens.ALICE) } },
                  { client.get("/api/v1/users/$name") { token(TestTokens.ALICE) } },
                  { client.get("/api/v1/users/$name") { cookie(dev) } },
                  { client.post("/api/v1/users/$name/disable") { browser(dev) } },
                  { client.post("/api/v1/users/$name/enable") { token(TestTokens.ALICE) } },
                  { client.get("/api/v1/users/$name/sessions") { browser(dev) } },
                  { client.delete("/api/v1/users/$name/sessions") { browser(dev) } },
                  { client.delete("/api/v1/users/$name/sessions/x") { token(TestTokens.ALICE) } },
              )
          val answers = asks.map { it() }
          assertTrue(
              answers.all { it.status == HttpStatusCode.Forbidden },
              "$name: ${answers.map { it.status }}",
          )
          assertEquals(
              1,
              answers.map { it.bodyAsText() }.toSet().size,
              "the answers differ with the name",
          )
        }
      }

  @Test
  fun `an administrator lists people and sees one, by token or by session`() = testApplication {
    engine()
    val admin = session("oidc-admin", Role.ADMIN)
    session("ann", Role.DEVELOPER)
    session("ann", Role.DEVELOPER)

    val byToken = client.get("/api/v1/users") { token(TestTokens.ROOT) }.json().jsonObject
    val bySession = client.get("/api/v1/users") { cookie(admin) }.json().jsonObject

    assertEquals(byToken, bySession)
    val users =
        byToken.getValue("users").jsonArray.associateBy {
          it.jsonObject.getValue("name").jsonPrimitive.content
        }
    assertEquals(setOf("ann", "oidc-admin"), users.keys)
    val ann = users.getValue("ann").jsonObject
    assertEquals(2, ann.getValue("sessions").jsonPrimitive.int)
    assertEquals(false, ann.getValue("disabled").jsonPrimitive.boolean)
    assertEquals("Display ann", ann.getValue("displayName").jsonPrimitive.content)
    val one = client.get("/api/v1/users/ann") { token(TestTokens.ROOT) }
    assertEquals(HttpStatusCode.OK, one.status)
    assertEquals(
        HttpStatusCode.NotFound,
        client.get("/api/v1/users/ghost") { token(TestTokens.ROOT) }.status,
    )
  }

  @Test
  fun `disabling ends the sessions at once and enabling lets the person back, who did it is told`() =
      testApplication {
        engine()
        val ann = session("ann", Role.DEVELOPER)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/system") { cookie(ann) }.status)

        val disabled = client.post("/api/v1/users/ann/disable") { token(TestTokens.ROOT) }

        assertEquals(HttpStatusCode.OK, disabled.status)
        val body = disabled.json().jsonObject
        assertEquals(true, body.getValue("disabled").jsonPrimitive.boolean)
        assertEquals("root", body.getValue("statusChangedBy").jsonPrimitive.content)
        assertEquals(1, body.getValue("sessionsEnded").jsonPrimitive.int)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/api/v1/system") { cookie(ann) }.status,
        )

        val enabled = client.post("/api/v1/users/ann/enable") { token(TestTokens.ROOT) }
        assertEquals(false, enabled.json().jsonObject.getValue("disabled").jsonPrimitive.boolean)
      }

  @Test
  fun `the name of an API token is an error, an unknown name is not found`() = testApplication {
    engine()

    val token = client.post("/api/v1/users/alice/disable") { token(TestTokens.ROOT) }
    assertEquals(HttpStatusCode.Conflict, token.status)
    assertEquals(
        "not_an_oidc_user",
        token.json().jsonObject.getValue("error").jsonPrimitive.content,
    )
    val ghost = client.post("/api/v1/users/ghost/disable") { token(TestTokens.ROOT) }
    assertEquals(HttpStatusCode.NotFound, ghost.status)
    assertEquals("user_not_found", ghost.json().jsonObject.getValue("error").jsonPrimitive.content)
  }

  @Test
  fun `sessions are listed without identifiers, and ended one by one or all`() = testApplication {
    engine()
    val first = session("ann", Role.DEVELOPER)
    val second = session("ann", Role.DEVELOPER)
    val third = session("ann", Role.DEVELOPER)

    val listed = client.get("/api/v1/users/ann/sessions") { token(TestTokens.ROOT) }
    val text = listed.bodyAsText()
    val sessions = Json.parseToJsonElement(text).jsonObject.getValue("sessions").jsonArray
    assertEquals(3, sessions.size)
    for (s in listOf(first, second, third)) assertFalse(
        text.contains(s.id) || text.contains(s.csrf)
    )
    assertEquals(setOf("id", "createdAt", "lastUsedAt"), sessions[0].jsonObject.keys)

    val handle = sessions[0].jsonObject.getValue("id").jsonPrimitive.content
    assertEquals(
        HttpStatusCode.NoContent,
        client.delete("/api/v1/users/ann/sessions/$handle") { token(TestTokens.ROOT) }.status,
    )
    assertEquals(
        HttpStatusCode.NotFound,
        client.delete("/api/v1/users/ann/sessions/$handle") { token(TestTokens.ROOT) }.status,
    )
    val survivors =
        listOf(first, second, third).count {
          client.get("/api/v1/system") { cookie(it) }.status == HttpStatusCode.OK
        }
    assertEquals(2, survivors)

    val all = client.delete("/api/v1/users/ann/sessions") { token(TestTokens.ROOT) }
    assertEquals(2, all.json().jsonObject.getValue("ended").jsonPrimitive.int)
    assertEquals(
        HttpStatusCode.NotFound,
        client.delete("/api/v1/users/ghost/sessions") { token(TestTokens.ROOT) }.status,
    )
  }

  @Test
  fun `an administrator by session needs the CSRF rules, and may disable themselves, the tokens stay`() =
      testApplication {
        engine()
        val admin = session("oidc-admin", Role.ADMIN)

        val without = client.post("/api/v1/users/ann/disable") { cookie(admin) }
        assertEquals(HttpStatusCode.Forbidden, without.status)

        val self = client.post("/api/v1/users/oidc-admin/disable") { browser(admin) }
        assertEquals(HttpStatusCode.OK, self.status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/api/v1/users") { cookie(admin) }.status,
        )
        // The way back: a token of an administrator is not touched by any of it.
        assertEquals(
            HttpStatusCode.OK,
            client.get("/api/v1/users") { token(TestTokens.ROOT) }.status,
        )
        assertEquals(
            HttpStatusCode.OK,
            client.post("/api/v1/users/oidc-admin/enable") { token(TestTokens.ROOT) }.status,
        )
      }
}
