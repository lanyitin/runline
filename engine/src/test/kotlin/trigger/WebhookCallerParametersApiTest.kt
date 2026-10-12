package dev.lawlan.runline.engine.trigger

import dev.lawlan.runline.engine.config.DatabaseConfig
import dev.lawlan.runline.engine.support.CapturedLogs
import dev.lawlan.runline.engine.support.PipelineJars
import dev.lawlan.runline.engine.support.RunHarness
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

/** Parameters provided by the caller of a webhook (ADR-036), against the real application. */
class WebhookCallerParametersApiTest {
  private val dir: Path = Files.createTempDirectory("webhook-caller-api")
  private val database: DatabaseConfig = migratedDatabase()
  private val safeList = "java.lang,java.util,java.io,kotlin,org.jetbrains.annotations"
  private val maxBody = 120
  private var counter = 0

  private fun ApplicationTestBuilder.engine(extra: Map<String, String> = emptyMap()) =
      configureEngine(
          database,
          mapOf("analysis.allowlist" to safeList, "webhook.maxBodyBytes" to "$maxBody") + extra,
      )

  private suspend fun HttpResponse.json(): JsonObject =
      Json.parseToJsonElement(bodyAsText()).jsonObject

  private fun JsonObject.text(key: String) = this[key]!!.jsonPrimitive.content

  private fun JsonObject.array(key: String) = this[key]!!.jsonArray

  private suspend fun ApplicationTestBuilder.uploaded(): String {
    val fqcn = "demo.W${counter++}"
    val declaration =
        RunHarness.DEFAULT_DECLARATION +
            ", parameters = {@Param(name = \"file\"), @Param(name = \"env\")}"
    val jar =
        PipelineJars.build(
            dir,
            "j-${System.nanoTime()}.jar",
            mapOf(fqcn to PipelineJars.pipeline(fqcn, "ingest", declaration, "", "")),
        )
    val response =
        client.post("/api/v1/artifacts") {
          header(HttpHeaders.Authorization, "Bearer ${TestTokens.ALICE}")
          contentType(ContentType.Application.OctetStream)
          setBody(Files.readAllBytes(jar))
        }
    assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
    return response.json().text("contentHash")
  }

  private suspend fun ApplicationTestBuilder.trigger(
      method: HttpMethod,
      path: String,
      body: String,
  ) =
      client.request(path) {
        this.method = method
        header(HttpHeaders.Authorization, "Bearer ${TestTokens.ROOT}")
        contentType(ContentType.Application.Json)
        setBody(body)
      }

  private fun hookBody(
      hash: String,
      kind: String = "webhook",
      params: String = """{"env":"prod"}""",
      caller: String = """["file"]""",
  ) =
      """{"name":"on-upload","kind":"$kind","contentHash":"$hash","pipeline":"ingest","parameters":$params,"callerParameters":$caller${if (kind == "cron") ""","cron":"0 2 * * *"""" else ""}}"""

  private suspend fun ApplicationTestBuilder.call(
      secret: String?,
      delivery: String?,
      body: String?,
  ): HttpResponse =
      client.post("/api/v1/webhooks/on-upload") {
        secret?.let { header(WEBHOOK_SECRET_HEADER, it) }
        delivery?.let { header(WEBHOOK_DELIVERY_HEADER, it) }
        body?.let {
          contentType(ContentType.Application.Json)
          setBody(it)
        }
      }

  private suspend fun ApplicationTestBuilder.runs(): List<JsonObject> =
      client
          .get("/api/v1/runs?limit=200") {
            header(HttpHeaders.Authorization, "Bearer ${TestTokens.ROOT}")
          }
          .json()
          .array("runs")
          .map { it.jsonObject }

  private suspend fun ApplicationTestBuilder.awaitRuns(count: Int): List<JsonObject> {
    val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
    while (System.nanoTime() < deadline) {
      val all = runs()
      if (all.size >= count && all.all { it.text("state") in setOf("SUCCEEDED", "FAILED") })
          return all
      kotlinx.coroutines.delay(25)
    }
    fail("expected $count finished runs")
  }

  private suspend fun ApplicationTestBuilder.createdHook(): String {
    val hash = uploaded()
    val created = trigger(HttpMethod.Post, "/api/v1/triggers", hookBody(hash))
    assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
    return created.json().text("secret")
  }

  // ---- managing the list ----

  @Test
  fun `a webhook trigger is created with the parameters its callers provide and shows them`() =
      testApplication {
        engine()
        val hash = uploaded()

        val created = trigger(HttpMethod.Post, "/api/v1/triggers", hookBody(hash))

        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val shown = created.json()["trigger"]!!.jsonObject
        assertEquals(
            listOf("file"),
            shown.array("callerParameters").map { it.jsonPrimitive.content },
        )
        val read =
            client.get("/api/v1/triggers/on-upload") {
              header(HttpHeaders.Authorization, "Bearer ${TestTokens.ROOT}")
            }
        assertEquals(
            listOf("file"),
            read.json().array("callerParameters").map { it.jsonPrimitive.content },
        )
      }

  @Test
  fun `a cron trigger with caller parameters is refused and says why`() = testApplication {
    engine()
    val hash = uploaded()

    val refused = trigger(HttpMethod.Post, "/api/v1/triggers", hookBody(hash, kind = "cron"))

    assertEquals(HttpStatusCode.UnprocessableEntity, refused.status)
    assertEquals("invalid_trigger", refused.json().text("error"))
    assertEquals("caller_parameters_not_allowed", refused.json().text("problem"))
  }

  @Test
  fun `a refused list names the parameter and changes nothing`() = testApplication {
    engine()
    val hash = uploaded()

    val undeclared =
        trigger(HttpMethod.Post, "/api/v1/triggers", hookBody(hash, caller = """["file","nope"]"""))
    assertEquals(HttpStatusCode.UnprocessableEntity, undeclared.status)
    assertEquals("invalid_parameters", undeclared.json().text("error"))
    assertEquals("nope", undeclared.json().array("problems").single().jsonObject.text("name"))
    assertEquals(
        "undeclared",
        undeclared.json().array("problems").single().jsonObject.text("problem"),
    )

    val both =
        trigger(HttpMethod.Post, "/api/v1/triggers", hookBody(hash, caller = """["file","env"]"""))
    assertEquals("env", both.json().array("problems").single().jsonObject.text("name"))
    assertEquals(
        "fixed_and_caller",
        both.json().array("problems").single().jsonObject.text("problem"),
    )

    val missing = trigger(HttpMethod.Post, "/api/v1/triggers", hookBody(hash, caller = "[]"))
    assertEquals("file", missing.json().array("problems").single().jsonObject.text("name"))
    assertEquals("missing", missing.json().array("problems").single().jsonObject.text("problem"))

    val list =
        client.get("/api/v1/triggers") {
          header(HttpHeaders.Authorization, "Bearer ${TestTokens.ROOT}")
        }
    assertEquals(0, list.json().array("triggers").size)
  }

  @Test
  fun `the list is changed with a patch`() = testApplication {
    engine()
    createdHook()

    val patched =
        trigger(
            HttpMethod.Patch,
            "/api/v1/triggers/on-upload",
            """{"parameters":{"file":"fixed.mp4"},"callerParameters":["env"]}""",
        )

    assertEquals(HttpStatusCode.OK, patched.status, patched.bodyAsText())
    assertEquals(
        listOf("env"),
        patched.json().array("callerParameters").map { it.jsonPrimitive.content },
    )
    assertEquals("fixed.mp4", patched.json()["parameters"]!!.jsonObject.text("file"))
  }

  // ---- calling ----

  @Test
  fun `a call with a body creates a run with the fixed parameters and the values of the caller`() =
      testApplication {
        engine()
        val secret = createdHook()

        val response = call(secret, "d-1", """{"file":"videos/a.mp4"}""")

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertEquals("""{"status":"accepted"}""", response.bodyAsText())
        val parameters = awaitRuns(1).single()["parameters"]!!.jsonObject
        assertEquals("videos/a.mp4", parameters.text("file"))
        assertEquals("prod", parameters.text("env"))
      }

  @Test
  fun `a body that is refused is answered 400, names the parameter and leaves the delivery usable`() =
      testApplication {
        engine()
        val secret = createdHook()

        val missing = call(secret, "d-1", """{}""")
        assertEquals(HttpStatusCode.BadRequest, missing.status)
        assertEquals("invalid_caller_parameters", missing.json().text("error"))
        val problem = missing.json().array("problems").single().jsonObject
        assertEquals("file", problem.text("name"))
        assertEquals("missing", problem.text("problem"))

        val extra = call(secret, "d-1", """{"file":"a","env":"evil"}""")
        assertEquals(HttpStatusCode.BadRequest, extra.status)
        assertEquals("env", extra.json().array("problems").single().jsonObject.text("name"))
        assertEquals(
            "undeclared",
            extra.json().array("problems").single().jsonObject.text("problem"),
        )

        val notText = call(secret, "d-1", """{"file":["a"]}""")
        assertEquals(
            "not_text",
            notText.json().array("problems").single().jsonObject.text("problem"),
        )

        val notObject = call(secret, "d-1", """["a"]""")
        assertEquals(HttpStatusCode.BadRequest, notObject.status)
        assertEquals("invalid_body", notObject.json().text("error"))
        assertEquals("not_a_json_object", notObject.json().text("problem"))

        val tooBig = call(secret, "d-1", """{"file":"${"x".repeat(maxBody)}"}""")
        assertEquals(HttpStatusCode.BadRequest, tooBig.status)
        assertEquals("invalid_body", tooBig.json().text("error"))
        assertEquals("too_large", tooBig.json().text("problem"))

        assertEquals(0, runs().size)
        assertEquals(HttpStatusCode.Accepted, call(secret, "d-1", """{"file":"a"}""").status)
        assertEquals(1, awaitRuns(1).size)
      }

  @Test
  fun `a value with a NUL character is answered 400 naming the parameter and leaves the delivery usable`() =
      testApplication {
        CapturedLogs().use { logs ->
          engine()
          val secret = createdHook()

          val refused = call(secret, "d-1", """{"file":"private-77\u0000b"}""")

          assertEquals(HttpStatusCode.BadRequest, refused.status)
          assertEquals("invalid_caller_parameters", refused.json().text("error"))
          val problem = refused.json().array("problems").single().jsonObject
          assertEquals("file", problem.text("name"))
          assertEquals("contains_nul", problem.text("problem"))
          assertFalse(refused.bodyAsText().contains("private-77"))
          assertEquals(0, runs().size)
          assertTrue(logs.at(ch.qos.logback.classic.Level.ERROR).isEmpty(), logs.lines.toString())
          assertEquals(HttpStatusCode.Accepted, call(secret, "d-1", """{"file":"a"}""").status)
          assertEquals(1, awaitRuns(1).size)
        }
      }

  @Test
  fun `the answer to a refused body never contains a value`() = testApplication {
    engine()
    val secret = createdHook()

    val answers =
        listOf(
                call(secret, "d-1", """{"file":"secret-value-77","nope":"secret-value-77"}"""),
                call(secret, "d-1", """{"file":12345678}"""),
                call(secret, "d-1", "secret-value-77"),
            )
            .map { it.bodyAsText() }

    answers.forEach { assertFalse(it.contains("secret-value-77") || it.contains("12345678"), it) }
  }

  @Test
  fun `credentials are checked before the body and their failures are unchanged`() =
      testApplication {
        engine()
        val secret = createdHook()
        val garbage = "\u0000 not json"

        val wrong = call("wrong", "d-1", garbage)
        val unknown =
            client.post("/api/v1/webhooks/nobody") {
              header(WEBHOOK_SECRET_HEADER, "wrong")
              header(WEBHOOK_DELIVERY_HEADER, "d-1")
              setBody(garbage)
            }
        val noDelivery = call(secret, null, garbage)

        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        assertEquals(wrong.bodyAsText(), unknown.bodyAsText())
        assertEquals(HttpStatusCode.BadRequest, noDelivery.status)
        assertEquals("invalid_delivery_id", noDelivery.json().text("error"))
      }

  @Test
  fun `a repeated delivery gets the same 202 and its body is ignored`() = testApplication {
    engine()
    val secret = createdHook()
    val first = call(secret, "d-1", """{"file":"a"}""")

    val again = call(secret, "d-1", "garbage that would be refused")

    assertEquals(HttpStatusCode.Accepted, again.status)
    assertEquals(first.bodyAsText(), again.bodyAsText())
    assertEquals(1, awaitRuns(1).size)
  }

  @Test
  fun `neither the values nor the secret appear in the logs`() = testApplication {
    CapturedLogs().use { logs ->
      engine()
      val secret = createdHook()

      call(secret, "d-1", """{"file":"private-file-name-9913","x":"private-file-name-9913"}""")
      call(secret, "d-1", """{"file":"private-file-name-9913"}""")
      awaitRuns(1)

      val text = logs.lines.joinToString("\n")
      assertFalse(text.contains("private-file-name-9913"))
      assertFalse(text.contains(secret))
    }
  }
}
