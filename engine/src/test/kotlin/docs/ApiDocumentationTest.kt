package dev.lawlan.runline.engine.docs

import dev.lawlan.runline.engine.artifact.ReasonKind
import dev.lawlan.runline.engine.artifact.RejectionReason
import dev.lawlan.runline.engine.support.TestTokens
import dev.lawlan.runline.engine.support.configureEngine
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

/**
 * Keeps `08-api.md` from drifting away from the Engine: the routes that are really registered must
 * be exactly the ones documented, each with the authentication the route really requires, and every
 * error code the routes can answer with must appear in the document. What this cannot check is the
 * prose: field lists and the meaning of each status stay the author's to update.
 */
class ApiDocumentationTest {
  private data class Endpoint(val method: String, val path: String, val auth: String)

  private val documentText = Files.readString(Path.of(System.getProperty("runline.apiDoc")))

  private val documented: List<Endpoint> =
      Regex("^### `([A-Z]+) (/\\S*)`\\s*\\n+認證：(.+)$", RegexOption.MULTILINE)
          .findAll(documentText)
          .map { Endpoint(it.groupValues[1], it.groupValues[2], it.groupValues[3].trim()) }
          .toList()

  /** What the application really has registered, with the authentication each route requires. */
  private fun ApplicationTestBuilder.registered(): List<Endpoint> {
    val found = mutableListOf<Endpoint>()
    fun walk(route: Route) {
      if (route.children.none()) found += endpointOf(route.toString())
      route.children.forEach(::walk)
    }
    walk(application.plugin(RoutingRoot))
    return found
  }

  private fun endpointOf(route: String): Endpoint {
    val parts = route.split('/').filter { it.isNotEmpty() }
    // A WebSocket route has no method selector of its own; it is answered to a GET upgrade.
    val method =
        parts.firstOrNull { it.startsWith("(method:") }?.removePrefix("(method:")?.removeSuffix(")")
            ?: "GET"
    val role = parts.firstOrNull { it.startsWith("(authorized ") }?.removePrefix("(authorized ")
    val path = "/" + parts.filterNot { it.startsWith("(") }.joinToString("/")
    val auth =
        when {
          role != null -> "Bearer（${role.removeSuffix(")").lowercase()}）"
          path.startsWith("/api/v1/webhooks/") -> "專用標頭"
          else -> "無"
        }
    return Endpoint(method, path, auth)
  }

  @Test
  fun `the documented endpoints are exactly the registered routes`() = testApplication {
    configureEngine()
    startApplication()

    val actual = registered().map { it.method to it.path }.toSet()
    val docs = documented.map { it.method to it.path }

    assertEquals(docs.size, docs.toSet().size, "an endpoint is documented twice")
    assertEquals(
        emptySet(),
        actual - docs.toSet(),
        "routes missing from 08-api.md (add a '### `METHOD path`' section)",
    )
    assertEquals(
        emptySet(),
        docs.toSet() - actual,
        "08-api.md documents routes that do not exist",
    )
  }

  @Test
  fun `the Console is one documented mount point and nothing else is registered outside api and openapi`() =
      testApplication {
        configureEngine()
        startApplication()

        val outside =
            registered().filterNot {
              it.path.startsWith("/api/") ||
                  it.path == "/openapi" ||
                  it.path.startsWith("/openapi/")
            }

        // The Console takes every GET the rest does not; anything else outside /api would be
        // documented by the mount's section, and escape the rule that /api routes are listed one
        // by one (ADR-015, WI-31).
        assertEquals(listOf(Endpoint("GET", "/{...}", "無")), outside)
        assertTrue(documented.any { it.method == "GET" && it.path == "/{...}" })
      }

  @Test
  fun `each documented endpoint states the authentication its route requires`() = testApplication {
    configureEngine()
    startApplication()

    for (route in registered()) {
      val doc = documented.single { it.method == route.method && it.path == route.path }
      assertTrue(
          doc.auth.startsWith(route.auth),
          "${route.method} ${route.path}: the route requires '${route.auth}', the document says '${doc.auth}'",
      )
    }
  }

  @Test
  fun `a Bearer endpoint answers 401 without a token and an administrator endpoint 403 to a developer`() =
      testApplication {
        configureEngine()
        startApplication()
        val rest = createClient { expectSuccess = false }

        for (endpoint in documented.filter { it.auth.startsWith("Bearer") }) {
          // The WebSocket route is only reached by an upgrade request; its checks are the same.
          if (endpoint.path.endsWith("/log/stream")) continue
          val path = filled(endpoint.path)
          val anonymous = rest.request(path) { method = HttpMethod.parse(endpoint.method) }
          assertEquals(
              HttpStatusCode.Unauthorized,
              anonymous.status,
              "${endpoint.method} ${endpoint.path} without a token",
          )
          val asDeveloper =
              rest.request(path) {
                method = HttpMethod.parse(endpoint.method)
                header(HttpHeaders.Authorization, "Bearer ${TestTokens.ALICE}")
              }
          if (endpoint.auth.startsWith("Bearer（admin）")) {
            assertEquals(
                HttpStatusCode.Forbidden,
                asDeveloper.status,
                "${endpoint.method} ${endpoint.path} as a developer",
            )
          } else {
            assertNotEquals(HttpStatusCode.Forbidden, asDeveloper.status, endpoint.path)
            assertNotEquals(HttpStatusCode.Unauthorized, asDeveloper.status, endpoint.path)
          }
        }
      }

  @Test
  fun `the webhook entry is not behind a Bearer token and says so`() = testApplication {
    configureEngine()
    startApplication()
    val webhook = documented.single { it.path == "/api/v1/webhooks/{name}" }

    assertTrue(webhook.auth.contains("X-Runline-Webhook-Secret"), webhook.auth)
    assertTrue(webhook.auth.contains("不使用 Bearer"), "the document says it is not a Bearer endpoint")
    // A Bearer token does not open it; only the trigger's own header can.
    val withToken =
        client.post("/api/v1/webhooks/x") {
          header(HttpHeaders.Authorization, "Bearer ${TestTokens.ROOT}")
          header("X-Runline-Delivery-Id", "d1")
        }
    assertEquals(HttpStatusCode.Unauthorized, withToken.status)
    assertTrue(withToken.bodyAsText().contains("unauthorized"))
  }

  @Test
  fun `every error code the routes can answer with is in the document`() {
    val sources = Path.of(System.getProperty("runline.engineSources"))
    val codeLiteral = Regex("\"([a-z][a-z0-9]*(?:_[a-z0-9]+)+)\"")
    val files = Files.walk(sources).use { it.toList() }
    val fromRoutes =
        files
            .filter {
              val name = it.fileName.toString()
              name.endsWith("Routes.kt") ||
                  name == "RoleAuthorization.kt" ||
                  name == "StatusPages.kt" ||
                  name == "GracefulShutdown.kt"
            }
            .flatMap { file ->
              codeLiteral.findAll(Files.readString(file)).map { it.groupValues[1] }
            }
            .toSet()
    val codes =
        fromRoutes +
            RejectionReason.entries.map { it.name.lowercase() } +
            setOf("forbidden", "unauthorized")

    val missing = codes.filter { !documentText.contains("`$it`") }

    assertEquals(emptyList(), missing.sorted(), "error codes not documented in 08-api.md")
  }

  @Test
  fun `every reason kind of a verdict is in the document`() {
    val missing = ReasonKind.entries.map { it.name }.filter { !documentText.contains("`$it`") }

    assertEquals(emptyList(), missing, "reason kinds not documented in 08-api.md")
  }

  private fun filled(path: String) =
      path
          .replace("{contentHash}", "a".repeat(64))
          .replace("{runId}", UUID.randomUUID().toString())
          .replace("{pipeline}", "p")
          .replace("{name}", "x")
}
