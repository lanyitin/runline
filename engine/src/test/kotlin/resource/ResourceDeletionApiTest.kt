package dev.lawlan.runline.engine.resource

import ch.qos.logback.classic.Logger
import dev.lawlan.runline.engine.support.ResourceApiSupport
import dev.lawlan.runline.engine.support.RunHarness
import dev.lawlan.runline.engine.support.SnapshotListAppender
import dev.lawlan.runline.engine.support.TestTokens
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

/** Deleting shared resources through the whole Engine (WI-40, ADR-019 point 8). */
class ResourceDeletionApiTest : ResourceApiSupport() {
  private fun JsonObject.number(key: String) = this[key]!!.jsonPrimitive.int

  private suspend fun ApplicationTestBuilder.names() =
      get("/api/v1/resources", TestTokens.ROOT).json().array("resources").map { it.text("name") }

  // ---- deleting ----

  @Test
  fun `an administrator deletes a resource nobody uses and it is gone`() = testApplication {
    engine()
    define("lemonade")
    define("other")

    val response = remove("lemonade", token = ops)

    assertEquals(HttpStatusCode.NoContent, response.status, response.bodyAsText())
    assertEquals("", response.bodyAsText())
    assertEquals(HttpStatusCode.NotFound, get("/api/v1/resources/lemonade", ops).status)
    assertEquals(listOf("other"), names())
  }

  @Test
  fun `deleting a resource that does not exist is not found`() = testApplication {
    engine()

    val response = remove("ghost")

    assertEquals(HttpStatusCode.NotFound, response.status)
    assertEquals("resource_not_found", response.json().text("error"))
  }

  @Test
  fun `deleting needs an administrator`() = testApplication {
    engine()
    define("lemonade")

    assertEquals(HttpStatusCode.Unauthorized, remove("lemonade", token = null).status)
    assertEquals(HttpStatusCode.Forbidden, remove("lemonade", token = TestTokens.ALICE).status)
    assertEquals(
        HttpStatusCode.Forbidden,
        remove("lemonade", "?preview=true", token = TestTokens.ALICE).status,
    )
    assertEquals(listOf("lemonade"), names())
  }

  @Test
  fun `a resource a run holds cannot be deleted until the run lets go`() = testApplication {
    engine()
    define("lemonade")
    val hash = uploaded("holder", listOf("lemonade"), body = RunHarness.WAIT_FOR_STOP)
    val run = start(hash, "holder")
    awaitState(run, "RUNNING")

    val refused = remove("lemonade")

    assertEquals(HttpStatusCode.Conflict, refused.status)
    assertEquals("resource_in_use", refused.json().text("error"))
    assertEquals(1, refused.json().number("holders"))
    assertEquals(0, refused.json().number("waiters"))
    assertEquals(listOf(run), resource("lemonade").array("holders").map { it.text("runId") })
    cancel(run)
    awaitState(run, "CANCELLED")
    assertEquals(HttpStatusCode.NoContent, remove("lemonade").status)
  }

  @Test
  fun `a resource a run waits for cannot be deleted, even when the run waits for another one`() =
      testApplication {
        engine("runs.maxConcurrent" to "4")
        define("busy")
        define("free")
        val holderHash = uploaded("holder", listOf("busy"), body = RunHarness.WAIT_FOR_STOP)
        val waiterHash = uploaded("waiter", listOf("busy", "free"))
        val holder = start(holderHash, "holder")
        awaitState(holder, "RUNNING")
        val waiter = start(waiterHash, "waiter")
        awaitState(waiter, "WAITING_FOR_RESOURCES")

        val refused = remove("free")

        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals("resource_in_use", refused.json().text("error"))
        assertEquals(0, refused.json().number("holders"))
        assertEquals(1, refused.json().number("waiters"))
        assertEquals(setOf("busy", "free"), names().toSet())
        cancel(holder)
        awaitState(waiter, "SUCCEEDED")
      }

  @Test
  fun `a resource still declared by pipelines and triggers can be deleted, and their runs are then refused as unknown`() =
      testApplication {
        engine()
        define("lemonade")
        val hash = uploaded("needs", listOf("lemonade"))
        client.post("/api/v1/triggers") {
          bearer(TestTokens.ROOT)()
          contentType(ContentType.Application.Json)
          setBody(
              """{"name":"nightly","kind":"cron","contentHash":"$hash","pipeline":"needs",""" +
                  """"cron":"0 2 * * *","timeZone":"UTC"}"""
          )
        }
        awaitState(start(hash, "needs"), "SUCCEEDED")

        assertEquals(HttpStatusCode.NoContent, remove("lemonade").status)

        val response = createRun(hash, "needs")
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("resources_unavailable", response.json().text("error"))
        assertEquals(
            mapOf("lemonade" to "unknown"),
            response.json().array("problems").associate {
              it.text("resource") to it.text("problem")
            },
        )
      }

  @Test
  fun `a deleted name can be defined again with its own capacity`() = testApplication {
    engine()
    define("lemonade", 1)
    remove("lemonade")

    val again = define("lemonade", 5)

    assertEquals(HttpStatusCode.Created, again.status, again.bodyAsText())
    assertEquals(5, resource("lemonade").number("capacity"))
    assertEquals("counter", resource("lemonade").text("type"))
  }

  @Test
  fun `a delete is logged with the name of the administrator`() = testApplication {
    val logs = SnapshotListAppender().also { it.start() }
    val logger = LoggerFactory.getLogger(ResourceRemoval::class.java) as Logger
    logger.addAppender(logs)
    try {
      engine()
      define("lemonade")

      remove("lemonade", token = ops)

      assertTrue(
          logs.snapshot().any {
            it.formattedMessage.contains("lemonade") &&
                it.formattedMessage.contains("deleted") &&
                it.formattedMessage.contains("ops")
          },
          logs.snapshot().map { it.formattedMessage }.toString(),
      )
    } finally {
      logger.detachAppender(logs)
    }
  }

  // ---- preview ----

  @Test
  fun `a preview says how many definitions and triggers are affected and changes nothing`() =
      testApplication {
        engine()
        define("lemonade")
        val first = uploaded("first", listOf("lemonade"))
        uploaded("second", types = mapOf("lemonade" to "counter"))
        uploaded("unrelated", listOf("other"))
        client.post("/api/v1/triggers") {
          bearer(TestTokens.ROOT)()
          contentType(ContentType.Application.Json)
          setBody(
              """{"name":"nightly","kind":"cron","contentHash":"$first","pipeline":"first",""" +
                  """"cron":"0 2 * * *","timeZone":"UTC"}"""
          )
        }

        val response = remove("lemonade", "?preview=true")

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val preview = response.json()
        assertEquals("lemonade", preview.text("resource"))
        assertEquals(2, preview.number("definitions"))
        assertEquals(1, preview.number("triggers"))
        assertEquals(0, preview.number("holders"))
        assertEquals(0, preview.number("waiters"))
        assertEquals(JsonPrimitive(false), preview["inUse"])
        assertEquals(listOf("lemonade"), names())
        assertEquals(HttpStatusCode.NoContent, remove("lemonade", "?preview=false").status)
      }

  @Test
  fun `a preview of a resource in use succeeds and says it is in use, and the delete itself is refused`() =
      testApplication {
        engine()
        define("lemonade")
        val hash = uploaded("holder", listOf("lemonade"), body = RunHarness.WAIT_FOR_STOP)
        val run = start(hash, "holder")
        awaitState(run, "RUNNING")

        val preview = remove("lemonade", "?preview=true")

        assertEquals(HttpStatusCode.OK, preview.status, preview.bodyAsText())
        assertEquals(1, preview.json().number("holders"))
        assertEquals(JsonPrimitive(true), preview.json()["inUse"])
        assertEquals(HttpStatusCode.Conflict, remove("lemonade").status)
        assertEquals(listOf("lemonade"), names())
        cancel(run)
      }

  @Test
  fun `a preview of a resource that does not exist is not found, and a preview flag that is not true or false is refused`() =
      testApplication {
        engine()
        define("lemonade")

        val missing = remove("ghost", "?preview=true")
        val odd = remove("lemonade", "?preview=maybe")

        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals("resource_not_found", missing.json().text("error"))
        assertEquals(HttpStatusCode.BadRequest, odd.status)
        assertEquals("bad_request", odd.json().text("error"))
        assertEquals(listOf("lemonade"), names())
      }
}
