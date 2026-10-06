package dev.lawlan.runline.engine

import dev.lawlan.runline.engine.support.configureEngine
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.*

class ServerTest {

  @Test
  fun `swagger UI is served at openapi`() = testApplication {
    // loads default configuration
    configureEngine()
    // verify Swagger UI returns 200
    assertEquals(HttpStatusCode.OK, client.get("/openapi").status)
  }
}
