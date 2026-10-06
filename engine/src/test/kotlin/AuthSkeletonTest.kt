package dev.lawlan.runline.engine

import dev.lawlan.runline.engine.support.configureEngine
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.*

@OptIn(ExperimentalEncodingApi::class)
class AuthSkeletonTest {

  @Test
  fun `no weak-credential protected route exists`() = testApplication {
    configureEngine()
    val response =
        client.get("/protected/route/basic") {
          header(
              HttpHeaders.Authorization,
              "Basic ${Base64.Default.encode("user:user".toByteArray())}",
          )
        }
    assertEquals(HttpStatusCode.NotFound, response.status)
  }

  @Test
  fun `no oauth or form example routes exist`() = testApplication {
    configureEngine()
    assertEquals(HttpStatusCode.NotFound, client.get("/login").status)
    assertEquals(HttpStatusCode.NotFound, client.get("/callback").status)
    assertEquals(HttpStatusCode.NotFound, client.get("/protected/route/form").status)
  }
}
