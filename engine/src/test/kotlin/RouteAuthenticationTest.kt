package dev.lawlan.runline.engine

import dev.lawlan.runline.engine.support.configureEngine
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
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

  @Test
  fun `every registered route refuses a request without a token unless ADR-012 lists it`() =
      testApplication {
        configureEngine()
        startApplication()
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
            val response =
                rest.request(filled(route.path)) { method = HttpMethod.parse(route.method) }
            assertEquals(
                HttpStatusCode.Unauthorized,
                response.status,
                "${route.method} ${route.path} answered a request without a token",
            )
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
