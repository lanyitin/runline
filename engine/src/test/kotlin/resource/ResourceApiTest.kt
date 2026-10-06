package dev.lawlan.runline.engine.resource

import ch.qos.logback.classic.Logger
import dev.lawlan.runline.engine.config.DatabaseConfig
import dev.lawlan.runline.engine.support.PipelineJars
import dev.lawlan.runline.engine.support.RunHarness
import dev.lawlan.runline.engine.support.SnapshotListAppender
import dev.lawlan.runline.engine.support.TestTokens
import dev.lawlan.runline.engine.support.configureEngine
import dev.lawlan.runline.engine.support.migratedDatabase
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

class ResourceApiTest {
  private val dir: Path = Files.createTempDirectory("resource-api")
  private val database: DatabaseConfig = migratedDatabase()
  private val safeList = "java.lang,java.util,java.io,kotlin,org.jetbrains.annotations"
  private val ops = "tok-ops-0123456789"
  private var counter = 0

  private fun ApplicationTestBuilder.engine(vararg overrides: Pair<String, String>) =
      configureEngine(
          database,
          mapOf(
              "analysis.allowlist" to safeList,
              "auth.tokens" to "${TestTokens.API_TOKENS},ops:admin:$ops",
          ) + overrides,
      )

  private fun bearer(token: String?): HttpRequestBuilder.() -> Unit = {
    token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
  }

  private suspend fun HttpResponse.json(): JsonObject =
      Json.parseToJsonElement(bodyAsText()).jsonObject

  private fun JsonObject.text(key: String) = this[key]!!.jsonPrimitive.content

  private fun JsonObject.array(key: String) = this[key]!!.jsonArray.map { it.jsonObject }

  private suspend fun ApplicationTestBuilder.define(
      name: String,
      capacity: Int = 1,
      token: String? = TestTokens.ROOT,
  ): HttpResponse =
      client.post("/api/v1/resources") {
        bearer(token)()
        contentType(ContentType.Application.Json)
        setBody("""{"name":"$name","capacity":$capacity}""")
      }

  private suspend fun ApplicationTestBuilder.change(
      name: String,
      body: String,
      token: String? = TestTokens.ROOT,
  ): HttpResponse =
      client.patch("/api/v1/resources/$name") {
        bearer(token)()
        contentType(ContentType.Application.Json)
        setBody(body)
      }

  private suspend fun ApplicationTestBuilder.get(path: String, token: String?): HttpResponse =
      client.get(path) { bearer(token)() }

  private suspend fun ApplicationTestBuilder.resource(
      name: String,
      token: String = TestTokens.ROOT,
  ) = get("/api/v1/resources/$name", token).json()

  private suspend fun ApplicationTestBuilder.upload(
      name: String,
      resources: List<String> = emptyList(),
      body: String = "",
      token: String = TestTokens.ALICE,
  ): HttpResponse {
    val fqcn = "demo.R${counter++}"
    val declaration =
        if (resources.isEmpty()) RunHarness.DEFAULT_DECLARATION
        else
            RunHarness.DEFAULT_DECLARATION +
                ", resources = {" +
                resources.joinToString { "\"$it\"" } +
                "}"
    val jar =
        PipelineJars.build(
            dir,
            "j-${System.nanoTime()}.jar",
            mapOf(fqcn to PipelineJars.pipeline(fqcn, name, declaration, "", body)),
        )
    return client.post("/api/v1/artifacts") {
      bearer(token)()
      contentType(ContentType.Application.OctetStream)
      setBody(Files.readAllBytes(jar))
    }
  }

  private suspend fun ApplicationTestBuilder.uploaded(
      name: String,
      resources: List<String> = emptyList(),
      body: String = "",
  ): String {
    val response = upload(name, resources, body)
    assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
    return response.json().text("contentHash")
  }

  private suspend fun ApplicationTestBuilder.createRun(
      hash: String,
      pipeline: String,
      token: String? = TestTokens.ALICE,
  ): HttpResponse =
      client.post("/api/v1/runs") {
        bearer(token)()
        contentType(ContentType.Application.Json)
        setBody("""{"contentHash":"$hash","pipeline":"$pipeline"}""")
      }

  private suspend fun ApplicationTestBuilder.start(hash: String, pipeline: String): String {
    val response = createRun(hash, pipeline)
    assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
    return response.json().text("runId")
  }

  private suspend fun ApplicationTestBuilder.awaitState(
      id: String,
      vararg states: String,
  ): JsonObject {
    val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
    while (System.nanoTime() < deadline) {
      val run = get("/api/v1/runs/$id", TestTokens.ROOT).json()
      if (run.text("state") in states) return run
      kotlinx.coroutines.delay(25)
    }
    fail("run $id did not reach ${states.toList()}")
  }

  private suspend fun ApplicationTestBuilder.cancel(id: String) =
      client.post("/api/v1/runs/$id/cancel") { bearer(TestTokens.ALICE)() }

  // ---- authentication and authorization ----

  @Test
  fun `every resource endpoint requires authentication`() = testApplication {
    engine()
    val run = "00000000-0000-0000-0000-000000000001"

    assertEquals(HttpStatusCode.Unauthorized, define("a", token = null).status)
    assertEquals(HttpStatusCode.Unauthorized, get("/api/v1/resources", null).status)
    assertEquals(HttpStatusCode.Unauthorized, get("/api/v1/resources/a", "wrong").status)
    assertEquals(
        HttpStatusCode.Unauthorized,
        change("a", """{"capacity":2}""", token = null).status,
    )
    assertEquals(
        HttpStatusCode.Unauthorized,
        client.post("/api/v1/resources/a/holders/$run/release").status,
    )
  }

  @Test
  fun `a developer cannot use any resource endpoint, not even to look`() = testApplication {
    engine()
    define("a")
    val run = "00000000-0000-0000-0000-000000000001"
    val alice = TestTokens.ALICE

    assertEquals(HttpStatusCode.Forbidden, define("b", token = alice).status)
    assertEquals(HttpStatusCode.Forbidden, get("/api/v1/resources", alice).status)
    assertEquals(HttpStatusCode.Forbidden, get("/api/v1/resources/a", alice).status)
    assertEquals(HttpStatusCode.Forbidden, change("a", """{"capacity":2}""", token = alice).status)
    assertEquals(
        HttpStatusCode.Forbidden,
        client.post("/api/v1/resources/a/holders/$run/release") { bearer(alice)() }.status,
    )
    assertEquals(1, resource("a")["capacity"]!!.jsonPrimitive.int)
    assertEquals(HttpStatusCode.NotFound, get("/api/v1/resources/b", TestTokens.ROOT).status)
  }

  // ---- defining and changing ----

  @Test
  fun `an administrator defines a resource and it records who and when`() = testApplication {
    engine()

    val response = define("lemonade", 2)

    assertEquals(HttpStatusCode.Created, response.status)
    assertEquals("/api/v1/resources/lemonade", response.headers[HttpHeaders.Location])
    val created = response.json()
    assertEquals("lemonade", created.text("name"))
    assertEquals(2, created["capacity"]!!.jsonPrimitive.int)
    assertTrue(created["enabled"]!!.jsonPrimitive.boolean)
    assertEquals("root", created.text("createdBy"))
    assertEquals("root", created.text("updatedBy"))
    assertEquals(emptyList(), created.array("holders"))
    assertEquals(emptyList(), created.array("waiters"))
    assertEquals(created, resource("lemonade"))
  }

  @Test
  fun `defining a name that exists, or something invalid, is refused with a reason`() =
      testApplication {
        engine()
        define("lemonade", 2)

        val again = define("lemonade", 5)
        val noCapacity = define("other", 0)
        val badName = define("a b", 1)
        val malformed =
            client.post("/api/v1/resources") {
              bearer(TestTokens.ROOT)()
              contentType(ContentType.Application.Json)
              setBody("""{"name":"x"}""")
            }

        assertEquals(HttpStatusCode.Conflict, again.status)
        assertEquals("resource_exists", again.json().text("error"))
        assertEquals(2, resource("lemonade")["capacity"]!!.jsonPrimitive.int)
        assertEquals(HttpStatusCode.UnprocessableEntity, noCapacity.status)
        assertEquals("invalid_resource", noCapacity.json().text("error"))
        assertTrue(noCapacity.json().text("message").contains("容量"))
        assertEquals(HttpStatusCode.UnprocessableEntity, badName.status)
        assertEquals("invalid_resource", badName.json().text("error"))
        assertTrue(badName.json().text("message").contains("名稱"))
        assertEquals(HttpStatusCode.BadRequest, malformed.status)
        assertEquals("bad_request", malformed.json().text("error"))
      }

  @Test
  fun `capacity and enabled flag are changed by an administrator, attributed to that administrator`() =
      testApplication {
        engine()
        define("lemonade", 1)

        val resized = change("lemonade", """{"capacity":3}""", token = ops)
        val disabled = change("lemonade", """{"enabled":false}""")

        assertEquals(HttpStatusCode.OK, resized.status)
        assertEquals(3, resized.json()["capacity"]!!.jsonPrimitive.int)
        assertEquals("ops", resized.json().text("updatedBy"))
        assertEquals("root", resized.json().text("createdBy"))
        assertFalse(disabled.json()["enabled"]!!.jsonPrimitive.boolean)
        assertEquals(3, disabled.json()["capacity"]!!.jsonPrimitive.int)
        assertEquals("root", disabled.json().text("updatedBy"))
        val list = get("/api/v1/resources", TestTokens.ROOT).json().array("resources")
        assertEquals(listOf("lemonade"), list.map { it.text("name") })
      }

  @Test
  fun `a change that is invalid or empty is refused and a resource that does not exist is not found`() =
      testApplication {
        engine()
        define("lemonade", 2)

        val zero = change("lemonade", """{"capacity":0}""")
        val empty = change("lemonade", "{}")
        val broken = change("lemonade", "not json")
        val missing = change("ghost", """{"capacity":2}""")

        assertEquals(HttpStatusCode.UnprocessableEntity, zero.status)
        assertEquals("invalid_resource", zero.json().text("error"))
        assertEquals(HttpStatusCode.UnprocessableEntity, empty.status)
        assertEquals("invalid_resource", empty.json().text("error"))
        assertEquals(HttpStatusCode.BadRequest, broken.status)
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals("resource_not_found", missing.json().text("error"))
        assertEquals(
            HttpStatusCode.NotFound,
            get("/api/v1/resources/ghost", TestTokens.ROOT).status,
        )
        assertEquals(2, resource("lemonade")["capacity"]!!.jsonPrimitive.int)
      }

  // ---- holders and waiters ----

  @Test
  fun `an administrator sees who holds a resource and who waits, in the order they will be served`() =
      testApplication {
        engine("runs.maxConcurrent" to "4")
        define("lemonade", 1)
        val holder = uploaded("holder", listOf("lemonade"), RunHarness.WAIT_FOR_STOP)
        val waiting = uploaded("waiting", listOf("lemonade"), RunHarness.WAIT_FOR_STOP)
        val first = start(holder, "holder")
        awaitState(first, "RUNNING")
        val second = start(waiting, "waiting")
        awaitState(second, "WAITING_FOR_RESOURCES")
        val third = start(waiting, "waiting")
        awaitState(third, "WAITING_FOR_RESOURCES")

        val view = resource("lemonade")

        val holders = view.array("holders")
        assertEquals(listOf(first), holders.map { it.text("runId") })
        assertEquals("holder", holders.single().text("pipeline"))
        assertNotNull(holders.single()["heldSince"])
        assertTrue(holders.single()["heldSeconds"]!!.jsonPrimitive.double >= 0.0)
        val waiters = view.array("waiters")
        assertEquals(listOf(second, third), waiters.map { it.text("runId") })
        assertEquals(listOf("waiting", "waiting"), waiters.map { it.text("pipeline") })
        assertNotNull(waiters.first()["waitingSince"])
        assertTrue(waiters.first()["waitedSeconds"]!!.jsonPrimitive.double >= 0.0)
        assertEquals(
            listOf("lemonade"),
            waiters.first()["waitingFor"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        val inList = get("/api/v1/resources", TestTokens.ROOT).json().array("resources").single()
        assertEquals(
            holders.map { it.text("runId") },
            inList.array("holders").map { it.text("runId") },
        )
        assertEquals(
            waiters.map { it.text("runId") },
            inList.array("waiters").map { it.text("runId") },
        )
        // clean up so the engine can stop
        cancel(first)
        awaitState(second, "RUNNING")
        cancel(second)
        awaitState(third, "RUNNING")
        cancel(third)
      }

  // ---- forced release ----

  @Test
  fun `an administrator forces a holder to let go, it is logged with who did it, and the next run proceeds`() =
      testApplication {
        val logs = SnapshotListAppender().also { it.start() }
        val logger = LoggerFactory.getLogger(ResourceCoordinator::class.java) as Logger
        logger.addAppender(logs)
        try {
          engine()
          define("lemonade", 1)
          val stuck = uploaded("stuck", listOf("lemonade"), RunHarness.WAIT_FOR_STOP)
          val next = uploaded("next", listOf("lemonade"))
          val first = start(stuck, "stuck")
          awaitState(first, "RUNNING")
          val second = start(next, "next")
          awaitState(second, "WAITING_FOR_RESOURCES")

          val response =
              client.post("/api/v1/resources/lemonade/holders/$first/release") { bearer(ops)() }

          assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
          assertEquals(first, response.json().text("runId"))
          assertEquals("lemonade", response.json().text("resource"))
          awaitState(second, "SUCCEEDED")
          assertEquals("RUNNING", get("/api/v1/runs/$first", TestTokens.ROOT).json().text("state"))
          assertEquals(emptyList(), resource("lemonade").array("holders"))
          assertTrue(
              logs.snapshot().any {
                it.formattedMessage.contains("by force") &&
                    it.formattedMessage.contains("ops") &&
                    it.formattedMessage.contains(first)
              }
          )
          cancel(first)
        } finally {
          logger.detachAppender(logs)
        }
      }

  @Test
  fun `forcing a release that does not apply says what was not found`() = testApplication {
    engine()
    define("lemonade", 1)
    val run = "00000000-0000-0000-0000-000000000001"

    val notHeld =
        client.post("/api/v1/resources/lemonade/holders/$run/release") { bearer(TestTokens.ROOT)() }
    val noResource =
        client.post("/api/v1/resources/ghost/holders/$run/release") { bearer(TestTokens.ROOT)() }
    val badId =
        client.post("/api/v1/resources/lemonade/holders/not-a-uuid/release") {
          bearer(TestTokens.ROOT)()
        }

    assertEquals(HttpStatusCode.NotFound, notHeld.status)
    assertEquals("not_a_holder", notHeld.json().text("error"))
    assertEquals(HttpStatusCode.NotFound, noResource.status)
    assertEquals("resource_not_found", noResource.json().text("error"))
    assertEquals(HttpStatusCode.NotFound, badId.status)
  }

  // ---- creating a run ----

  @Test
  fun `a run whose pipeline declares a missing or disabled resource is refused saying which, and leaves no run`() =
      testApplication {
        engine()
        define("off", 1)
        change("off", """{"enabled":false}""")
        val hash = uploaded("needs", listOf("ghost", "off"))

        val response = createRun(hash, "needs")

        assertEquals(HttpStatusCode.Conflict, response.status)
        val body = response.json()
        assertEquals("resources_unavailable", body.text("error"))
        val problems =
            body.array("problems").associate { it.text("resource") to it.text("problem") }
        assertEquals(mapOf("ghost" to "unknown", "off" to "disabled"), problems)
        assertTrue(body.text("message").contains("ghost"))
        assertTrue(body.text("message").contains("off"))
        val runs = get("/api/v1/runs", TestTokens.ROOT).json().array("runs")
        assertEquals(emptyList(), runs)
      }

  @Test
  fun `a run whose resources are defined and enabled is accepted`() = testApplication {
    engine()
    define("lemonade", 1)
    val hash = uploaded("needs", listOf("lemonade"))

    val id = start(hash, "needs")

    awaitState(id, "SUCCEEDED")
    assertEquals(emptyList(), resource("lemonade").array("holders"))
  }

  // ---- upload warnings ----

  @Test
  fun `an upload that declares a missing or disabled resource is judged as before and shows a warning`() =
      testApplication {
        engine()
        define("off", 1)
        change("off", """{"enabled":false}""")
        define("fine", 1)

        val response = upload("needs", listOf("ghost", "off", "fine"))

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val pipeline = response.json().array("pipelines").single()
        assertEquals("SAFE", pipeline.text("verdict"))
        val warnings = pipeline.array("warnings")
        assertEquals(
            mapOf("ghost" to "resource_unknown", "off" to "resource_disabled"),
            warnings.associate { it.text("resource") to it.text("kind") },
        )
        assertTrue(warnings.all { it.text("message").isNotBlank() })
      }

  @Test
  fun `a pipeline whose resources all exist shows no warnings, and an unknown resource stops warning once defined`() =
      testApplication {
        engine()
        define("fine", 1)
        val clean = upload("clean", listOf("fine")).json().array("pipelines").single()
        val hash = uploaded("late", listOf("later"))
        val before =
            get("/api/v1/definitions", TestTokens.ALICE).json().array("definitions").single {
              it.text("name") == "late"
            }

        define("later", 1)
        val after =
            get("/api/v1/definitions", TestTokens.ALICE).json().array("definitions").single {
              it.text("name") == "late"
            }

        assertEquals(emptyList(), clean.array("warnings"))
        assertEquals(1, before.array("warnings").size)
        assertEquals(emptyList(), after.array("warnings"))
        assertEquals(before["verdict"], after["verdict"])
        assertEquals(
            emptyList(),
            get("/api/v1/artifacts/$hash", TestTokens.ALICE)
                .json()
                .array("pipelines")
                .single()
                .array("warnings"),
        )
      }

  // ---- wait timeout through the whole Engine ----

  @Test
  fun `a run that waits longer than the configured limit fails with the reason, and the holder carries on`() =
      testApplication {
        engine("runs.resourceWaitTimeoutSeconds" to "1")
        define("lemonade", 1)
        val holder = uploaded("holder", listOf("lemonade"), RunHarness.WAIT_FOR_STOP)
        val needs = uploaded("needs", listOf("lemonade"))
        val first = start(holder, "holder")
        awaitState(first, "RUNNING")

        val second = start(needs, "needs")

        val ended = awaitState(second, "FAILED")
        val failure = ended["failure"]!!.jsonObject
        assertEquals("ResourceWaitTimeout", failure.text("type"))
        assertTrue(failure.text("message").contains("等待資源逾時"))
        assertEquals(JsonNull, ended["startedAt"])
        assertEquals("RUNNING", get("/api/v1/runs/$first", TestTokens.ROOT).json().text("state"))
        assertEquals(listOf(first), resource("lemonade").array("holders").map { it.text("runId") })
        cancel(first)
      }
}
