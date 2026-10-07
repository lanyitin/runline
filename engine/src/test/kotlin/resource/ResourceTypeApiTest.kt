package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.engine.support.ResourceApiSupport
import dev.lawlan.runline.engine.support.TestTokens
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*
import kotlinx.serialization.json.*

/** The type of a shared resource through the whole Engine (WI-40, ADR-019). */
class ResourceTypeApiTest : ResourceApiSupport() {
  private fun JsonObject.obj(key: String) = this[key]!!.jsonObject

  // ---- creating ----

  @Test
  fun `a resource defined with a name and a capacity only is a counter, as before`() =
      testApplication {
        engine()

        val response = define("lemonade", 2)

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val created = response.json()
        assertEquals("counter", created.text("type"))
        assertEquals(JsonObject(emptyMap()), created.obj("settings"))
        assertEquals(JsonNull, created["secretAlias"])
        assertEquals(created, resource("lemonade"))
      }

  @Test
  fun `a counter can be asked for by name`() = testApplication {
    engine()

    val response = define("lemonade", 1, """"type":"counter","settings":{}""")

    assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
    assertEquals("counter", response.json().text("type"))
  }

  @Test
  fun `the list and the single view both show type, settings and secret alias`() = testApplication {
    engine()
    define("lemonade", 1)

    val listed = get("/api/v1/resources", TestTokens.ROOT).json().array("resources").single()

    assertEquals("counter", listed.text("type"))
    assertEquals(JsonObject(emptyMap()), listed.obj("settings"))
    assertEquals(JsonNull, listed["secretAlias"])
  }

  @Test
  fun `a type that is not known, or not implemented yet, is refused saying why`() =
      testApplication {
        engine()

        val unknown = define("a", 1, """"type":"http-endpoint"""")
        val notYet = listOf("jdbc-pool").map { define("b-$it", 1, """"type":"$it"""") }

        assertEquals(HttpStatusCode.UnprocessableEntity, unknown.status)
        assertEquals("invalid_resource", unknown.json().text("error"))
        assertEquals("unknown_type", unknown.json().text("problem"))
        assertTrue(unknown.json().text("message").isNotBlank())
        notYet.forEach {
          assertEquals(HttpStatusCode.UnprocessableEntity, it.status)
          assertEquals("invalid_resource", it.json().text("error"))
          assertEquals("unsupported_type", it.json().text("problem"))
        }
        assertEquals(
            emptyList(),
            get("/api/v1/resources", TestTokens.ROOT).json().array("resources"),
        )
      }

  @Test
  fun `a counter given settings or a secret alias is refused`() = testApplication {
    engine()

    val settings = define("a", 1, """"settings":{"path":"x"}""")
    val alias = define("b", 1, """"secretAlias":"key"""")

    assertEquals(HttpStatusCode.UnprocessableEntity, settings.status)
    assertEquals("invalid_resource", settings.json().text("error"))
    assertEquals("invalid_settings", settings.json().text("problem"))
    assertEquals(HttpStatusCode.UnprocessableEntity, alias.status)
    assertEquals("invalid_secret_alias", alias.json().text("problem"))
    assertEquals(emptyList(), get("/api/v1/resources", TestTokens.ROOT).json().array("resources"))
  }

  @Test
  fun `every refusal as invalid_resource names its reason category`() = testApplication {
    engine()
    define("lemonade", 1)

    val badName = define("a b", 1)
    val badCapacity = define("ok", 0)
    val nothing = change("lemonade", "{}")
    val zero = change("lemonade", """{"capacity":0}""")

    assertEquals("name", badName.json().text("problem"))
    assertEquals("capacity", badCapacity.json().text("problem"))
    assertEquals("nothing_to_change", nothing.json().text("problem"))
    assertEquals("capacity", zero.json().text("problem"))
  }

  // ---- changing ----

  @Test
  fun `the type and the name cannot be changed`() = testApplication {
    engine()
    define("lemonade", 2)

    val retype = change("lemonade", """{"capacity":5,"type":"file"}""")
    val sameType = change("lemonade", """{"capacity":5,"type":"counter"}""")
    val rename = change("lemonade", """{"capacity":5,"name":"other"}""")

    listOf(retype to "immutable_type", sameType to "immutable_type", rename to "immutable_name")
        .forEach { (response, problem) ->
          assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
          assertEquals("invalid_resource", response.json().text("error"))
          assertEquals(problem, response.json().text("problem"))
        }
    assertEquals(2, resource("lemonade")["capacity"]!!.jsonPrimitive.int)
    assertEquals(HttpStatusCode.NotFound, get("/api/v1/resources/other", TestTokens.ROOT).status)
  }

  @Test
  fun `capacity and enabled flag are still changed as before`() = testApplication {
    engine()
    define("lemonade", 1)

    val changed = change("lemonade", """{"capacity":4,"enabled":false}""")

    assertEquals(HttpStatusCode.OK, changed.status)
    assertEquals(4, changed.json()["capacity"]!!.jsonPrimitive.int)
    assertEquals("counter", changed.json().text("type"))
  }

  @Test
  fun `a counter cannot be given settings by a change`() = testApplication {
    engine()
    define("lemonade", 1)

    val response = change("lemonade", """{"capacity":2,"settings":{"a":"b"}}""")

    assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
    assertEquals("invalid_settings", response.json().text("problem"))
  }
}
