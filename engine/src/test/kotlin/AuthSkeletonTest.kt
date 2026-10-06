package dev.lawlan.runline.engine

import dev.lawlan.runline.engine.support.configureEngine
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.*

@OptIn(ExperimentalEncodingApi::class)
class AuthSkeletonTest {
  /**
   * The paths of the routes the application registers. A GET of any other path is answered by the
   * Console (WI-31, ADR-015), so the absence of a route cannot be seen from a 404 any more.
   */
  private fun ApplicationTestBuilder.registeredPaths(): Set<String> {
    val found = mutableSetOf<String>()
    fun walk(route: Route) {
      if (route.children.none()) {
        found +=
            "/" +
                route
                    .toString()
                    .split('/')
                    .filter { it.isNotEmpty() && !it.startsWith("(") }
                    .joinToString("/")
      }
      route.children.forEach(::walk)
    }
    walk(application.plugin(RoutingRoot))
    return found
  }

  @Test
  fun `no weak-credential protected route exists`() = testApplication {
    configureEngine()
    startApplication()

    assertFalse("/protected/route/basic" in registeredPaths())
    // Nothing gives the weak credential more than the Console, which anyone gets.
    val response =
        client.post("/protected/route/basic") {
          header(
              HttpHeaders.Authorization,
              "Basic ${Base64.Default.encode("user:user".toByteArray())}",
          )
        }
    assertEquals(HttpStatusCode.NotFound, response.status)
    val api =
        client.get("/api/v1/system") {
          header(
              HttpHeaders.Authorization,
              "Basic ${Base64.Default.encode("user:user".toByteArray())}",
          )
        }
    assertEquals(HttpStatusCode.Unauthorized, api.status)
  }

  @Test
  fun `no oauth or form example routes exist`() = testApplication {
    configureEngine()
    startApplication()

    val paths = registeredPaths()
    for (path in listOf("/login", "/callback", "/protected/route/form")) {
      assertFalse(path in paths, path)
      assertEquals(HttpStatusCode.NotFound, client.post(path).status, path)
    }
  }
}
