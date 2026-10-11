package dev.lawlan.runline.engine

import dev.lawlan.runline.engine.support.TestTokens
import dev.lawlan.runline.engine.support.configureEngine
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import java.util.UUID
import kotlin.test.*

/**
 * ADR-012: an endpoint needs a Bearer token unless the ADR lists it. Every route the application
 * really registers is called without a token; only the listed ones may answer anything but 401, so
 * an endpoint added later without authentication fails here.
 */
class RouteAuthenticationTest {
  private data class Registered(val method: String, val path: String, val webSocket: Boolean)

  /** The endpoints ADR-012 lists as not needing a Bearer token. */
  private fun exempt(path: String): Boolean =
      path == "/openapi" ||
          path.startsWith("/openapi/") || // API documentation and its schema
          path == "/api/v1/info" || // the version and hash, for the login page (ADR-016)
          path == "/api/v1/health/live" || // the probes of the deployment platform (ADR-018)
          path == "/api/v1/health/ready" ||
          path == "/{...}" || // the Console's files and entry page, one mount point (ADR-015)
          path == "/api/v1/auth/methods" || // how a person can sign in (ADR-029)
          path == "/api/v1/auth/oidc/login" || // the start of the OIDC sign-in
          path == "/api/v1/auth/oidc/callback" || // its end, proven by the state
          path ==
              "/api/v1/auth/logout" || // ends the session of the cookie, if any; no cookie is fine
          path.startsWith("/api/v1/webhooks/") // per-trigger secret in its own header

  private fun ApplicationTestBuilder.registered(): List<Registered> {
    val found = mutableListOf<Registered>()
    fun walk(route: Route) {
      if (route.children.none()) found += parse(route.toString())
      route.children.forEach(::walk)
    }
    walk(application.plugin(RoutingRoot))
    return found
  }

  private fun parse(route: String): Registered {
    val parts = route.split('/').filter { it.isNotEmpty() }
    val method =
        parts.firstOrNull { it.startsWith("(method:") }?.removePrefix("(method:")?.removeSuffix(")")
            ?: "GET"
    val path = "/" + parts.filterNot { it.startsWith("(") }.joinToString("/")
    // A WebSocket route is selected by the upgrade headers of the handshake.
    return Registered(method, path, webSocket = "(header:Upgrade = websocket)" in parts)
  }

  private fun filled(path: String) =
      path
          .replace("{contentHash}", "a".repeat(64))
          .replace("{runId}", UUID.randomUUID().toString())
          .replace("{pipeline}", "p")
          .replace("{name}", "x")

  private suspend fun ApplicationTestBuilder.everyRouteRefusesAnonymous() {
    val rest = createClient { expectSuccess = false }
    val sockets = createClient { install(WebSockets) }

    val routes = registered()
    assertTrue(routes.size > 10, "routes were not enumerated: $routes")
    for (route in routes.filterNot { exempt(it.path) }) {
      if (route.webSocket) {
        // Only the handshake reaches it; an unauthenticated route would answer it with an
        // upgrade.
        val refusal = runCatching { sockets.webSocketSession(filled(route.path)) }
        assertTrue(
            refusal.isFailure,
            "WebSocket ${route.path} accepted a handshake without a token",
        )
      } else {
        val response = rest.request(filled(route.path)) { method = HttpMethod.parse(route.method) }
        assertEquals(
            HttpStatusCode.Unauthorized,
            response.status,
            "${route.method} ${route.path} answered a request without a token",
        )
      }
    }
  }

  @Test
  fun `every registered route refuses a request without a token unless ADR-012 lists it`() =
      testApplication {
        configureEngine()
        startApplication()

        everyRouteRefusesAnonymous()
      }

  @Test
  fun `with OIDC login on, the same holds and the session query refuses a request without a cookie`() =
      testApplication {
        configureEngine(
            overrides =
                mapOf(
                    "oidc.issuer" to "https://auth.example.test/application/o/runline/",
                    "oidc.clientId" to "runline",
                    "oidc.clientSecret" to "client-secret-value",
                    "oidc.publicBaseUrl" to "https://runline.example.test",
                    "session.encryptionKey" to "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                )
        )
        startApplication()

        everyRouteRefusesAnonymous()
        assertTrue(registered().any { it.path == "/api/v1/auth/session" })
        assertTrue(registered().any { it.path == "/api/v1/auth/oidc/callback" })
      }

  /** A session made the way a sign-in makes it; returns the identifier and the CSRF token. */
  private fun session(
      database: dev.lawlan.runline.engine.config.DatabaseConfig,
      name: String,
      role: dev.lawlan.runline.engine.auth.Role,
  ): Pair<String, String> {
    val id = dev.lawlan.runline.engine.oidc.SessionIds.newToken()
    val csrf = dev.lawlan.runline.engine.oidc.SessionIds.newToken()
    val now = java.time.Instant.now()
    dev.lawlan.runline.engine.oidc
        .PostgresSessionStore(dev.lawlan.runline.engine.db.dataSourceOf(database))
        .signIn(
            dev.lawlan.runline.engine.oidc.SignInIdentity(
                "https://auth.example.test/",
                "s-$name",
                name,
                name,
            ),
            dev.lawlan.runline.engine.oidc.NewSession(
                dev.lawlan.runline.engine.oidc.SessionIds.hash(id),
                role,
                csrf,
                byteArrayOf(1),
                now,
                now.plusSeconds(3600),
            ),
            now,
        )
    return id to csrf
  }

  @Test
  fun `with sessions, every endpoint judges a session as it judges a token and guards every change`() =
      testApplication {
        val database = dev.lawlan.runline.engine.support.migratedDatabase()
        val base = "https://runline.example.test"
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
        startApplication()
        val rest = createClient { expectSuccess = false }
        val sockets = createClient { install(WebSockets) }
        val people =
            listOf(
                Triple(
                    "developer",
                    TestTokens.ALICE,
                    session(database, "oidc-dev", dev.lawlan.runline.engine.auth.Role.DEVELOPER),
                ),
                Triple(
                    "admin",
                    TestTokens.ROOT,
                    session(database, "oidc-admin", dev.lawlan.runline.engine.auth.Role.ADMIN),
                ),
            )
        val unsafe = setOf("POST", "PUT", "PATCH", "DELETE")
        // The session query is the one route that is answered by a cookie alone, being the way a
        // session says who it is; the Bearer comparison does not apply to it.
        val routes = registered().filterNot { exempt(it.path) || it.path == "/api/v1/auth/session" }
        assertTrue(routes.size >= 30, "routes were not enumerated: ${routes.size}")
        // Everything WI-1200 and WI-1207 added is among the routes of the walk or the exempt ones.
        assertTrue(registered().any { it.path == "/api/v1/auth/session" })

        for ((role, token, session) in people) {
          val (id, csrf) = session
          for (route in routes) {
            val path = filled(route.path)
            val what = "$role ${route.method} ${route.path}"
            if (route.webSocket) {
              // A session is not enough for the stream; only the handshake is made.
              val refusal = runCatching {
                sockets.webSocketSession(path) { header(HttpHeaders.Cookie, "runline_session=$id") }
              }
              assertTrue(refusal.isFailure, "$what accepted a handshake with a session")
              continue
            }
            val method = HttpMethod.parse(route.method)
            val byToken =
                rest.request(path) {
                  this.method = method
                  header(HttpHeaders.Authorization, "Bearer $token")
                }
            val withoutGuard =
                rest.request(path) {
                  this.method = method
                  header(HttpHeaders.Cookie, "runline_session=$id")
                }
            val guarded =
                rest.request(path) {
                  this.method = method
                  header(HttpHeaders.Cookie, "runline_session=$id")
                  header("X-CSRF-Token", csrf)
                  header(HttpHeaders.Origin, base)
                }
            if (route.method in unsafe) {
              assertEquals(HttpStatusCode.Forbidden, withoutGuard.status, "$what without the guard")
              assertTrue(withoutGuard.bodyAsText().contains("origin_invalid"), what)
              val noToken =
                  rest.request(path) {
                    this.method = method
                    header(HttpHeaders.Cookie, "runline_session=$id")
                    header(HttpHeaders.Origin, base)
                  }
              assertEquals(HttpStatusCode.Forbidden, noToken.status, "$what without the token")
              assertTrue(noToken.bodyAsText().contains("csrf_invalid"), what)
            } else {
              assertEquals(byToken.status, withoutGuard.status, "$what: a read by a session")
            }
            assertEquals(byToken.status, guarded.status, "$what: a token and a session differ")
          }
        }
      }

  @Test
  fun `the endpoints left over from the template do not exist`() = testApplication {
    configureEngine()
    startApplication()
    val rest = createClient { expectSuccess = false }
    val token = "Bearer ${dev.lawlan.runline.engine.support.TestTokens.ROOT}"
    val paths = registered().map { it.path }.toSet()

    for (path in listOf("/ws", "/json/kotlinx-serialization", "/ktor/application/shutdown")) {
      // A GET of a path that is no route is answered by the Console (WI-31), so the routes
      // themselves are looked at; whatever else is tried gets 404.
      assertFalse(path in paths, path)
      for (method in listOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Delete)) {
        val withToken =
            rest.request(path) {
              this.method = method
              header(HttpHeaders.Authorization, token)
            }
        assertEquals(HttpStatusCode.NotFound, withToken.status, "$method $path with a token")
      }
    }
  }
}
