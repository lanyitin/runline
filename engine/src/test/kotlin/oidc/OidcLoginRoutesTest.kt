package dev.lawlan.runline.engine.oidc

import dev.lawlan.runline.engine.support.CapturedLogs
import dev.lawlan.runline.engine.support.KeycloakTestProvider
import dev.lawlan.runline.engine.support.MemoryTraceExporter
import dev.lawlan.runline.engine.support.PostgresTestContainer
import dev.lawlan.runline.engine.support.ProviderBrowser
import dev.lawlan.runline.engine.support.ProviderUser
import dev.lawlan.runline.engine.support.TestTokens
import dev.lawlan.runline.engine.support.configureEngine
import dev.lawlan.runline.engine.support.migratedDatabase
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import io.ktor.server.testing.*
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.sdk.OpenTelemetrySdk
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Signing in through the real application (WI-1200, 05-ipc): the HTTP surface of the login with a
 * real PostgreSQL and a real Keycloak. A browser is played by following the redirects by hand.
 */
class OidcLoginRoutesTest {
  private val database = migratedDatabase()
  private val key =
      Base64.getEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

  private fun oidcSettings(
      base: String = KeycloakTestProvider.PUBLIC_BASE_URL,
      issuer: String = KeycloakTestProvider.issuer,
      clientId: String = KeycloakTestProvider.CLIENT_ID,
  ) =
      mapOf(
          "oidc.issuer" to issuer,
          "oidc.clientId" to clientId,
          "oidc.clientSecret" to KeycloakTestProvider.CLIENT_SECRET,
          "oidc.publicBaseUrl" to base,
          "session.encryptionKey" to key,
      )

  private fun ApplicationTestBuilder.engine(vararg overrides: Pair<String, String>) {
    configureEngine(database, mapOf("ktor.di.conflictPolicy" to "OverridePrevious") + overrides)
  }

  private fun ApplicationTestBuilder.browser() = createClient {
    expectSuccess = false
    followRedirects = false
  }

  private fun count(table: String): Int =
      PostgresTestContainer.connect(database).use { c ->
        c.createStatement().use { s ->
          s.executeQuery("SELECT count(*) FROM $table").use {
            it.next()
            it.getInt(1)
          }
        }
      }

  private fun URI.pathAndQuery() = rawPath + (rawQuery?.let { "?$it" } ?: "")

  /** Goes to the login start and to the provider, signs in, and returns the callback request. */
  private suspend fun HttpClient.startAndSignIn(
      user: ProviderUser,
      returnPath: String? = null,
      base: String = KeycloakTestProvider.PUBLIC_BASE_URL,
  ): String {
    val start =
        get(
            "/api/v1/auth/oidc/login" +
                (returnPath?.let { "?return=" + it.encodeURLParameter() } ?: "")
        )
    assertEquals(HttpStatusCode.Found, start.status)
    val back =
        ProviderBrowser(base)
            .signIn(URI(start.headers[HttpHeaders.Location]!!), user.username, user.password)
    return back.pathAndQuery()
  }

  private fun HttpResponse.sessionCookie(): Cookie? =
      setCookie().firstOrNull { it.name == SESSION_COOKIE }

  private suspend fun HttpClient.session(cookie: String?) =
      get("/api/v1/auth/session") {
        cookie?.let { header(HttpHeaders.Cookie, "$SESSION_COOKIE=$it") }
      }

  private fun loginError(response: HttpResponse): String? =
      response.headers[HttpHeaders.Location]?.let { URI(it).rawQuery?.removePrefix("login_error=") }

  // ---- off ----

  @Test
  fun `without the settings login is off, the methods say so and nothing else exists`() =
      testApplication {
        engine()
        val client = browser()

        val methods = client.get("/api/v1/auth/methods")
        assertEquals(HttpStatusCode.OK, methods.status)
        assertEquals(
            false,
            Json.parseToJsonElement(methods.bodyAsText())
                .jsonObject
                .getValue("oidc")
                .jsonObject
                .getValue("enabled")
                .jsonPrimitive
                .content
                .toBoolean(),
        )
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/auth/oidc/login").status)
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/v1/auth/oidc/callback?code=c&state=s").status,
        )
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/auth/session").status)
        assertEquals(HttpStatusCode.NotFound, client.post("/api/v1/auth/logout").status)
        // The tokens work as they did.
        assertEquals(
            HttpStatusCode.OK,
            client
                .get("/api/v1/system") {
                  header(HttpHeaders.Authorization, "Bearer ${TestTokens.ROOT}")
                }
                .status,
        )
      }

  // ---- on ----

  @Test
  fun `with the settings the methods say login is on`() = testApplication {
    engine(*oidcSettings().toList().toTypedArray())

    val methods = browser().get("/api/v1/auth/methods")

    assertEquals(
        true,
        Json.parseToJsonElement(methods.bodyAsText())
            .jsonObject
            .getValue("oidc")
            .jsonObject
            .getValue("enabled")
            .jsonPrimitive
            .content
            .toBoolean(),
    )
  }

  @Test
  fun `the login start sends the browser to the provider and keeps no response in a cache`() =
      testApplication {
        engine(*oidcSettings().toList().toTypedArray())

        val start = browser().get("/api/v1/auth/oidc/login")

        assertEquals(HttpStatusCode.Found, start.status)
        assertTrue(
            start.headers[HttpHeaders.Location]!!.startsWith(
                "${KeycloakTestProvider.issuer}/protocol/openid-connect/auth?"
            ),
        )
        assertEquals("no-store", start.headers[HttpHeaders.CacheControl])
        assertEquals(1, count("login_attempt"))
      }

  @Test
  fun `a person signs in, gets a cookie, is told who they are, and signs out`() = testApplication {
    engine(*oidcSettings().toList().toTypedArray())
    val client = browser()
    val user = KeycloakTestProvider.createUser(listOf("runline-admin"))

    val callback = client.get(client.startAndSignIn(user, returnPath = "/runs/7?tab=log"))

    assertEquals(HttpStatusCode.Found, callback.status)
    assertEquals("/runs/7?tab=log", callback.headers[HttpHeaders.Location])
    assertEquals("no-store", callback.headers[HttpHeaders.CacheControl])
    // The address the browser was sent to carries the code; it is not passed on to the next page.
    assertEquals("no-referrer", callback.headers["Referrer-Policy"])
    val cookie = assertNotNull(callback.sessionCookie())
    assertTrue(cookie.httpOnly)
    assertFalse(cookie.secure, "the public address is http")
    assertEquals("Lax", cookie.extensions["SameSite"])
    assertEquals("/", cookie.path)
    assertTrue(cookie.value.length >= 43)

    val session = client.session(cookie.value)
    assertEquals(HttpStatusCode.OK, session.status)
    assertEquals("no-store", session.headers[HttpHeaders.CacheControl])
    val body = Json.parseToJsonElement(session.bodyAsText()).jsonObject
    assertEquals(user.username, body.getValue("name").jsonPrimitive.content)
    assertEquals("admin", body.getValue("role").jsonPrimitive.content)
    assertEquals(user.displayName, body.getValue("displayName").jsonPrimitive.content)
    assertTrue(body.getValue("csrfToken").jsonPrimitive.content.length >= 32)

    val csrf = body.getValue("csrfToken").jsonPrimitive.content
    val out =
        client.post("/api/v1/auth/logout") {
          header(HttpHeaders.Cookie, "$SESSION_COOKIE=${cookie.value}")
          header("X-CSRF-Token", csrf)
          header(HttpHeaders.Origin, KeycloakTestProvider.PUBLIC_BASE_URL)
        }
    assertEquals(HttpStatusCode.NoContent, out.status)
    val cleared = assertNotNull(out.sessionCookie())
    assertEquals(0, cleared.maxAge)
    assertTrue(cleared.httpOnly)
    assertEquals("/", cleared.path)
    assertEquals(HttpStatusCode.Unauthorized, client.session(cookie.value).status)
    assertEquals(0, count("user_session"))

    // Signing out again, and with no cookie, is no failure.
    assertEquals(
        HttpStatusCode.NoContent,
        client
            .post("/api/v1/auth/logout") {
              header(HttpHeaders.Cookie, "$SESSION_COOKIE=${cookie.value}")
            }
            .status,
    )
    assertEquals(HttpStatusCode.NoContent, client.post("/api/v1/auth/logout").status)
  }

  @Test
  fun `the cookie is Secure when the public address is https`() = testApplication {
    val base = "https://runline.example.test"
    engine(*oidcSettings(base = base).toList().toTypedArray())
    val client = browser()
    val user = KeycloakTestProvider.createUser(listOf("runline-developer"))

    val callback = client.get(client.startAndSignIn(user, base = base))

    val cookie = assertNotNull(callback.sessionCookie())
    assertTrue(cookie.secure)
    assertTrue(cookie.httpOnly)
    assertEquals("Lax", cookie.extensions["SameSite"])
    assertTrue(callback.headers[HttpHeaders.SetCookie]!!.contains("Secure"))
    assertTrue(
        client.session(cookie.value).bodyAsText().contains("developer"),
    )
  }

  @Test
  fun `a return to another place than this site is not followed`() = testApplication {
    engine(*oidcSettings().toList().toTypedArray())
    val client = browser()
    val user = KeycloakTestProvider.createUser(listOf("runline-developer"))

    for (bad in listOf("https://evil.example/x", "//evil.example", "/\\evil.example")) {
      val callback = client.get(client.startAndSignIn(user, returnPath = bad))
      assertEquals("/", callback.headers[HttpHeaders.Location], bad)
    }
  }

  @Test
  fun `no cookie, an unknown cookie or the cookie of an ended session is a 401`() =
      testApplication {
        engine(*oidcSettings().toList().toTypedArray())
        val client = browser()

        assertEquals(HttpStatusCode.Unauthorized, client.session(null).status)
        assertEquals(HttpStatusCode.Unauthorized, client.session("made-up").status)
      }

  @Test
  fun `the session cookie opens the API as a token does, for a read`() = testApplication {
    engine(*oidcSettings().toList().toTypedArray())
    val client = browser()
    val user = KeycloakTestProvider.createUser(listOf("runline-admin"))
    val cookie = client.get(client.startAndSignIn(user)).sessionCookie()!!

    val response =
        client.get("/api/v1/system") {
          header(HttpHeaders.Cookie, "$SESSION_COOKIE=${cookie.value}")
        }

    assertEquals(HttpStatusCode.OK, response.status)
  }

  // ---- what goes wrong ----

  @Test
  fun `a failed return goes to the Console with the code of the failure and sets no cookie`() =
      testApplication {
        val clash = KeycloakTestProvider.createUser(listOf("runline-developer"))
        engine(
            *oidcSettings().toList().toTypedArray(),
            "auth.tokens" to
                "${TestTokens.API_TOKENS},${clash.username}:developer:tok-clash-0123456789",
        )
        val client = browser()
        val nobody = KeycloakTestProvider.createUser(listOf("staff"))

        val notAuthorized = client.get(client.startAndSignIn(nobody))
        assertEquals("/?login_error=not_authorized", notAuthorized.headers[HttpHeaders.Location])
        assertNull(notAuthorized.sessionCookie())

        val conflict = client.get(client.startAndSignIn(clash))
        assertEquals("/?login_error=name_conflict", conflict.headers[HttpHeaders.Location])
        assertNull(conflict.sessionCookie())

        val unknown = client.get("/api/v1/auth/oidc/callback?code=c&state=nope")
        assertEquals("/?login_error=invalid_state", unknown.headers[HttpHeaders.Location])

        val start = client.get("/api/v1/auth/oidc/login")
        val state =
            URI(start.headers[HttpHeaders.Location]!!)
                .rawQuery
                .split('&')
                .first { it.startsWith("state=") }
                .removePrefix("state=")
        val denied = client.get("/api/v1/auth/oidc/callback?error=access_denied&state=$state")
        assertEquals("/?login_error=access_denied", denied.headers[HttpHeaders.Location])
        assertEquals(0, count("user_session"))
        assertEquals(0, count("oidc_user"))
      }

  @Test
  fun `the same return twice makes one session`() = testApplication {
    engine(*oidcSettings().toList().toTypedArray())
    val client = browser()
    val user = KeycloakTestProvider.createUser(listOf("runline-developer"))
    val callback = client.startAndSignIn(user)

    val first = client.get(callback)
    val second = client.get(callback)

    assertNotNull(first.sessionCookie())
    assertEquals("/?login_error=invalid_state", second.headers[HttpHeaders.Location])
    assertNull(second.sessionCookie())
    assertEquals(1, count("user_session"))
  }

  @Test
  fun `a provider that cannot be reached sends the browser back with a code at the start, and does not touch start or readiness`() =
      testApplication {
        engine(*oidcSettings(issuer = "http://localhost:1/realms/runline").toList().toTypedArray())
        val client = browser()

        // The Engine started without asking the provider, and is ready.
        startApplication()
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/health/ready").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/auth/methods").status)

        val start = client.get("/api/v1/auth/oidc/login")
        assertEquals(HttpStatusCode.Found, start.status)
        assertEquals("/?login_error=provider_unavailable", start.headers[HttpHeaders.Location])
        assertEquals("no-store", start.headers[HttpHeaders.CacheControl])
        assertEquals(0, count("login_attempt"))
      }

  @Test
  fun `a provider that gives no refresh token, or signs with the secret, sends the browser back with a code`() =
      testApplication {
        val user = KeycloakTestProvider.createUser(listOf("runline-developer"))
        engine(*oidcSettings(clientId = "runline-norefresh").toList().toTypedArray())
        val client = browser()

        val noRefresh = client.get(client.startAndSignIn(user))

        assertEquals("/?login_error=refresh_unavailable", noRefresh.headers[HttpHeaders.Location])
        assertNull(noRefresh.sessionCookie())
        assertEquals(0, count("user_session"))
        assertEquals(0, count("oidc_user"))
      }

  @Test
  fun `an ID token signed with the client secret sends the browser back with invalid_token`() =
      testApplication {
        val user = KeycloakTestProvider.createUser(listOf("runline-developer"))
        engine(*oidcSettings(clientId = "runline-hs256").toList().toTypedArray())
        val client = browser()

        val response = client.get(client.startAndSignIn(user))

        assertEquals("/?login_error=invalid_token", response.headers[HttpHeaders.Location])
        assertNull(response.sessionCookie())
        assertEquals(0, count("user_session"))
      }

  @Test
  fun `beyond the room for sign-ins the start sends the browser back with a code, and one under way finishes`() =
      testApplication {
        engine(*oidcSettings().toList().toTypedArray(), "oidc.maxPendingLogins" to "2")
        val client = browser()
        val user = KeycloakTestProvider.createUser(listOf("runline-developer"))
        val first = client.get("/api/v1/auth/oidc/login")
        client.get("/api/v1/auth/oidc/login")

        val refused = client.get("/api/v1/auth/oidc/login")

        assertEquals(HttpStatusCode.Found, refused.status)
        assertEquals("/?login_error=too_many_logins", refused.headers[HttpHeaders.Location])
        assertEquals("no-store", refused.headers[HttpHeaders.CacheControl])
        assertEquals(2, count("login_attempt"))

        val back =
            ProviderBrowser()
                .signIn(URI(first.headers[HttpHeaders.Location]!!), user.username, user.password)
        val callback = client.get(back.pathAndQuery())
        assertNotNull(callback.sessionCookie())
      }

  // ---- observability ----

  @Test
  fun `the code and the state of a return are in no span, log or metric of the request`() {
    val logs = CapturedLogs()
    try {
      MemoryTraceExporter.recording {
        testApplication {
          engine(*oidcSettings().toList().toTypedArray())
          val client = browser()
          val user = KeycloakTestProvider.createUser(listOf("runline-developer"))
          val callback = client.startAndSignIn(user)
          val cookie = client.get(callback).sessionCookie()!!
          client.session(cookie.value)
          (application.dependencies.resolve<OpenTelemetry>() as OpenTelemetrySdk)
              .sdkTracerProvider
              .forceFlush()
              .join(30, TimeUnit.SECONDS)

          val spans = MemoryTraceExporter.spans.finishedSpanItems
          val secrets =
              URI(callback).rawQuery.split('&').map { it.substringAfter('=') } + cookie.value
          val recorded = spans.map { it.toString() } + logs.lines
          assertTrue(spans.any { it.name == "auth.login.complete" }, "the login is traced")
          assertTrue(
              spans.any {
                it.attributes.asMap().keys.any { k ->
                  k.key.startsWith("url") || k.key.startsWith("http")
                }
              },
              "the request is traced by the server instrumentation",
          )
          for (secret in secrets) {
            assertTrue(
                recorded.none { it.contains(secret) },
                "a secret of ${secret.length} characters is recorded",
            )
          }
        }
      }
    } finally {
      logs.close()
    }
  }

  private companion object {
    const val SESSION_COOKIE = "runline_session"
  }
}
