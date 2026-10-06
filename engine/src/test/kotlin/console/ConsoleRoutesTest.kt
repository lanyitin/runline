package dev.lawlan.runline.engine.console

import dev.lawlan.runline.engine.support.TestTokens
import dev.lawlan.runline.engine.support.configureEngine
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import io.ktor.server.testing.*
import kotlin.test.*

/**
 * The Console on the real application (WI-31, ADR-015): real files from the test resources
 * (`console-fixture`, in the place the frontend build puts the real ones), a real PostgreSQL. The
 * files are given through Ktor's DI, which is how the Engine takes them in production.
 */
class ConsoleRoutesTest {
  private fun ApplicationTestBuilder.engine(
      assets: ConsoleAssets = ClasspathConsoleAssets("console-fixture")
  ) {
    // The test's module runs after the Engine's, so its declaration has to replace the Engine's.
    application { dependencies { provide<ConsoleAssets> { assets } } }
    configureEngine(overrides = mapOf("ktor.di.conflictPolicy" to "OverridePrevious"))
  }

  private fun ApplicationTestBuilder.rest() = createClient { expectSuccess = false }

  @Test
  fun `the root answers with the entry page`() = testApplication {
    engine()

    val response = rest().get("/")

    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals(ContentType.Text.Html, response.contentType()?.withoutParameters())
    assertTrue(response.bodyAsText().contains("fixture-entry-page"))
  }

  @Test
  fun `a file of the Console is answered with its content`() = testApplication {
    engine()

    val response = rest().get("/assets/app-3f9a1c.js")

    assertEquals(HttpStatusCode.OK, response.status)
    assertTrue(response.bodyAsText().contains("fixture-script"))
    assertEquals("javascript", response.contentType()!!.contentSubtype.removePrefix("x-"))
  }

  @Test
  fun `a file with a hash in its name is cached for a long time and the entry page is not`() =
      testApplication {
        engine()

        val asset = rest().get("/assets/app-3f9a1c.js").headers[HttpHeaders.CacheControl]
        val entry = rest().get("/").headers[HttpHeaders.CacheControl]
        val icon = rest().get("/favicon.svg").headers[HttpHeaders.CacheControl]

        assertEquals("public, max-age=31536000, immutable", asset)
        assertEquals("no-cache", entry)
        assertEquals("no-cache", icon)
      }

  @Test
  fun `a path of the single page application gets the entry page`() = testApplication {
    engine()

    for (path in listOf("/pipelines", "/pipelines/etl-nightly/runs/42", "/assets", "/login")) {
      val response = rest().get(path)

      assertEquals(HttpStatusCode.OK, response.status, path)
      assertTrue(response.bodyAsText().contains("fixture-entry-page"), path)
      assertEquals("no-cache", response.headers[HttpHeaders.CacheControl], path)
    }
  }

  @Test
  fun `a path that looks like a file but is none is 404 and not the entry page`() =
      testApplication {
        engine()

        for (path in listOf("/missing.js", "/assets/missing-abc123.js", "/robots.txt")) {
          val response = rest().get(path)

          assertEquals(HttpStatusCode.NotFound, response.status, path)
          assertFalse(response.bodyAsText().contains("fixture-entry-page"), path)
        }
      }

  @Test
  fun `under api and openapi an unknown path is 404 and never the entry page`() = testApplication {
    engine()

    for (path in
        listOf(
            "/api",
            "/api/",
            "/api/v1/nothing",
            "/api/v2/runs",
            "/openapi/nothing",
            "/openapi/x/y",
        )) {
      val response = rest().get(path)

      assertEquals(HttpStatusCode.NotFound, response.status, path)
      assertFalse(response.bodyAsText().contains("fixture-entry-page"), path)
    }
  }

  @Test
  fun `a path that only starts like api belongs to the application`() = testApplication {
    engine()

    assertEquals(HttpStatusCode.OK, rest().get("/apiary").status)
  }

  @Test
  fun `the API is not shadowed by the Console`() = testApplication {
    engine()

    val info = rest().get("/api/v1/info")
    val runs = rest().get("/api/v1/runs")
    val swagger = rest().get("/openapi")
    val system = rest().get("/api/v1/system")
    val withToken =
        rest().get("/api/v1/system") {
          header(HttpHeaders.Authorization, "Bearer ${TestTokens.ALICE}")
        }

    assertEquals(HttpStatusCode.OK, info.status)
    assertTrue(info.bodyAsText().contains("commitHash"))
    assertEquals(HttpStatusCode.Unauthorized, runs.status)
    assertEquals(HttpStatusCode.OK, swagger.status)
    assertFalse(swagger.bodyAsText().contains("fixture-entry-page"))
    assertEquals(HttpStatusCode.Unauthorized, system.status)
    assertEquals(HttpStatusCode.OK, withToken.status)
  }

  @Test
  fun `other methods than GET get no entry page`() = testApplication {
    engine()

    for (method in listOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Delete)) {
      val response = rest().request("/pipelines/x") { this.method = method }

      assertEquals(HttpStatusCode.NotFound, response.status, "$method")
    }
  }

  @Test
  fun `a path that climbs out of the Console does not give any other file`() = testApplication {
    engine()

    for (path in
        listOf(
            "/..%2fapplication.yaml",
            "/%2e%2e/application.yaml",
            "/assets/..%2f..%2fapplication.yaml",
        )) {
      val response = rest().get(path)
      val body = response.bodyAsText()

      assertFalse(body.contains("postgres:"), path)
      assertFalse(body.contains("ktor:"), path)
    }
  }

  @Test
  fun `every file of the Console carries the security headers`() = testApplication {
    engine()

    for (path in listOf("/", "/assets/app-3f9a1c.js", "/favicon.svg", "/some/spa/path")) {
      val headers = rest().get(path).headers
      val csp = assertNotNull(headers["Content-Security-Policy"], path)

      for (directive in
          listOf(
              "default-src 'self'",
              "script-src 'self'",
              "style-src 'self'",
              "font-src 'self'",
              "connect-src 'self'",
              "object-src 'none'",
              "base-uri 'none'",
              "form-action 'self'",
              "frame-ancestors 'none'",
          )) {
        assertTrue(
            csp.split(";").map { it.trim() }.contains(directive),
            "$path: $directive in $csp",
        )
      }
      assertFalse(csp.contains("unsafe-inline"), csp)
      assertFalse(csp.contains("unsafe-eval"), csp)
      assertFalse(csp.contains("http:"), csp)
      assertEquals("nosniff", headers["X-Content-Type-Options"], path)
      assertEquals("DENY", headers["X-Frame-Options"], path)
      assertEquals("no-referrer", headers["Referrer-Policy"], path)
    }
  }

  @Test
  fun `the Engine does not enable CORS`() = testApplication {
    engine()

    for (path in listOf("/", "/api/v1/info", "/assets/app-3f9a1c.js")) {
      val response = rest().get(path) { header(HttpHeaders.Origin, "https://elsewhere.example") }

      assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin], path)
    }
  }

  @Test
  fun `without a built Console the root is 404 and the API is not affected`() = testApplication {
    engine(ClasspathConsoleAssets("no-such-console"))

    assertEquals(HttpStatusCode.NotFound, rest().get("/").status)
    assertEquals(HttpStatusCode.NotFound, rest().get("/pipelines").status)
    assertEquals(HttpStatusCode.OK, rest().get("/api/v1/info").status)
    assertEquals(HttpStatusCode.Unauthorized, rest().get("/api/v1/runs").status)
  }

  @Test
  fun `a stopping Engine turns requests for the Console away like all others`() = testApplication {
    engine()
    startApplication()
    assertEquals(HttpStatusCode.OK, rest().get("/").status)

    application.monitor.raise(ApplicationStopPreparing, application.environment)

    for (path in listOf("/", "/assets/app-3f9a1c.js", "/pipelines/x")) {
      val response = rest().get(path)

      assertEquals(HttpStatusCode.ServiceUnavailable, response.status, path)
      assertTrue(response.bodyAsText().contains("shutting_down"), path)
    }
  }
}
