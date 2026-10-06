package dev.lawlan.runline.engine.trigger

import dev.lawlan.runline.engine.config.DatabaseConfig
import dev.lawlan.runline.engine.fixtures.DefaultsPipeline
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*

/** The trigger management API and the webhook entry, against the real application. */
class TriggerApiTest {
  private val dir: Path = Files.createTempDirectory("trigger-api")
  private val database: DatabaseConfig = migratedDatabase()
  private val safeList = "java.lang,java.util,java.io,kotlin,org.jetbrains.annotations"
  private val ops = "tok-ops-0123456789"
  private var counter = 0

  private fun ApplicationTestBuilder.engine() =
      configureEngine(
          database,
          mapOf(
              "analysis.allowlist" to safeList,
              "auth.tokens" to "${TestTokens.API_TOKENS},ops:admin:$ops",
          ),
      )

  private fun bearer(token: String?): HttpRequestBuilder.() -> Unit = {
    token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
  }

  private suspend fun HttpResponse.json(): JsonObject =
      Json.parseToJsonElement(bodyAsText()).jsonObject

  private fun JsonObject.text(key: String) = this[key]!!.jsonPrimitive.content

  private fun JsonObject.obj(key: String) = this[key]!!.jsonObject

  private fun JsonObject.array(key: String) = this[key]!!.jsonArray.map { it.jsonObject }

  private suspend fun ApplicationTestBuilder.upload(bytes: ByteArray): String {
    val response =
        client.post("/api/v1/artifacts") {
          bearer(TestTokens.ALICE)()
          contentType(ContentType.Application.OctetStream)
          setBody(bytes)
        }
    assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
    return response.json().text("contentHash")
  }

  /** Uploads a pipeline [name] that declares the parameter `env`, plus [body]. */
  private suspend fun ApplicationTestBuilder.uploaded(
      name: String,
      body: String = "",
      declaration: String =
          RunHarness.DEFAULT_DECLARATION + ", parameters = {@Param(name = \"env\")}",
  ): String {
    val fqcn = "demo.T${counter++}"
    val jar =
        PipelineJars.build(
            dir,
            "j-${System.nanoTime()}.jar",
            mapOf(fqcn to PipelineJars.pipeline(fqcn, name, declaration, "", body)),
        )
    return upload(Files.readAllBytes(jar))
  }

  private suspend fun ApplicationTestBuilder.uploadedDefaults(): String =
      upload(
          Files.readAllBytes(
              PipelineJars.jarOf(dir, "d-${System.nanoTime()}.jar", DefaultsPipeline::class.java)
          )
      )

  private suspend fun ApplicationTestBuilder.send(
      method: HttpMethod,
      path: String,
      token: String?,
      body: String? = null,
  ): HttpResponse =
      client.request(path) {
        this.method = method
        bearer(token)()
        body?.let {
          contentType(ContentType.Application.Json)
          setBody(it)
        }
      }

  private suspend fun ApplicationTestBuilder.createTrigger(
      body: String,
      token: String? = TestTokens.ROOT,
  ) = send(HttpMethod.Post, "/api/v1/triggers", token, body)

  private fun cronBody(
      name: String,
      hash: String,
      pipeline: String,
      extra: String = "",
      params: String = """{"env":"prod"}""",
  ) =
      """{"name":"$name","kind":"cron","contentHash":"$hash","pipeline":"$pipeline","parameters":$params,"cron":"0 2 * * *"$extra}"""

  private fun hookBody(
      name: String,
      hash: String,
      pipeline: String,
      params: String = """{"env":"prod"}""",
      extra: String = "",
  ) =
      """{"name":"$name","kind":"webhook","contentHash":"$hash","pipeline":"$pipeline","parameters":$params$extra}"""

  private suspend fun ApplicationTestBuilder.webhook(
      name: String,
      secret: String?,
      delivery: String?,
      body: String? = null,
  ): HttpResponse =
      client.post("/api/v1/webhooks/$name") {
        secret?.let { header(WEBHOOK_SECRET_HEADER, it) }
        delivery?.let { header(WEBHOOK_DELIVERY_HEADER, it) }
        body?.let {
          contentType(ContentType.Application.Json)
          setBody(it)
        }
      }

  private suspend fun ApplicationTestBuilder.runs(): List<JsonObject> =
      send(HttpMethod.Get, "/api/v1/runs?limit=200", TestTokens.ROOT).json().array("runs")

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

  // ---- authentication and authorization ----

  @Test
  fun `every management endpoint requires authentication`() = testApplication {
    engine()
    val paths =
        listOf(
            HttpMethod.Post to "/api/v1/triggers",
            HttpMethod.Get to "/api/v1/triggers",
            HttpMethod.Get to "/api/v1/triggers/a",
            HttpMethod.Patch to "/api/v1/triggers/a",
            HttpMethod.Delete to "/api/v1/triggers/a",
            HttpMethod.Post to "/api/v1/triggers/a/rotate-secret",
            HttpMethod.Get to "/api/v1/triggers/a/firings",
        )
    paths.forEach { (method, path) ->
      assertEquals(
          HttpStatusCode.Unauthorized,
          send(method, path, null, "{}").status,
          "$method $path",
      )
      assertEquals(
          HttpStatusCode.Unauthorized,
          send(method, path, "wrong", "{}").status,
          "$method $path",
      )
    }
  }

  @Test
  fun `a developer cannot use any management endpoint, not even to look`() = testApplication {
    engine()
    val hash = uploaded("nightly")
    createTrigger(cronBody("a", hash, "nightly"))
    val paths =
        listOf(
            HttpMethod.Post to "/api/v1/triggers",
            HttpMethod.Get to "/api/v1/triggers",
            HttpMethod.Get to "/api/v1/triggers/a",
            HttpMethod.Patch to "/api/v1/triggers/a",
            HttpMethod.Delete to "/api/v1/triggers/a",
            HttpMethod.Post to "/api/v1/triggers/a/rotate-secret",
            HttpMethod.Get to "/api/v1/triggers/a/firings",
        )
    paths.forEach { (method, path) ->
      assertEquals(
          HttpStatusCode.Forbidden,
          send(method, path, TestTokens.ALICE, """{"enabled":false}""").status,
          "$method $path",
      )
    }
    // Nothing was changed by the refused calls.
    val trigger = send(HttpMethod.Get, "/api/v1/triggers/a", TestTokens.ROOT).json()
    assertEquals("true", trigger.text("enabled"))
    assertEquals("0 2 * * *", trigger.text("cron"))
  }

  // ---- creating and looking at triggers ----

  @Test
  fun `an administrator creates a cron trigger bound to a version and it records who made it`() =
      testApplication {
        engine()
        val hash = uploaded("nightly")

        val response =
            createTrigger(
                cronBody("every-night", hash, "nightly", extra = ""","timeZone":"Asia/Taipei"""")
            )

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        assertEquals("/api/v1/triggers/every-night", response.headers[HttpHeaders.Location])
        val body = response.json()
        assertNull(body["secret"]?.takeIf { it !is JsonNull })
        val trigger = body.obj("trigger")
        assertEquals("every-night", trigger.text("name"))
        assertEquals("cron", trigger.text("kind"))
        assertEquals(hash, trigger.text("contentHash"))
        assertEquals("nightly", trigger.text("pipeline"))
        assertEquals("prod", trigger.obj("parameters").text("env"))
        assertEquals("0 2 * * *", trigger.text("cron"))
        assertEquals("Asia/Taipei", trigger.text("timeZone"))
        assertEquals("true", trigger.text("enabled"))
        assertEquals("root", trigger.text("createdBy"))
        assertEquals("root", trigger.text("updatedBy"))
        assertEquals(
            trigger,
            send(HttpMethod.Get, "/api/v1/triggers/every-night", TestTokens.ROOT).json(),
        )
      }

  @Test
  fun `a cron trigger without a time zone uses UTC and the effective parameters show the defaults`() =
      testApplication {
        engine()
        val hash = uploadedDefaults()

        createTrigger(cronBody("d", hash, "defaults"))

        val trigger = send(HttpMethod.Get, "/api/v1/triggers/d", TestTokens.ROOT).json()
        assertEquals("UTC", trigger.text("timeZone"))
        assertEquals(setOf("env"), trigger.obj("parameters").keys)
        assertEquals(
            mapOf("env" to "prod", "retries" to "3"),
            trigger.obj("effectiveParameters").mapValues { it.value.jsonPrimitive.content },
        )
      }

  @Test
  fun `a webhook trigger shows its secret once and no query ever shows it again`() =
      testApplication {
        engine()
        val hash = uploaded("nightly")

        val created = createTrigger(hookBody("on-push", hash, "nightly"))

        assertEquals(HttpStatusCode.Created, created.status)
        val secret = created.json().text("secret")
        assertTrue(secret.length >= 43)
        val trigger = created.json().obj("trigger")
        assertEquals("webhook", trigger.text("kind"))
        assertEquals("/api/v1/webhooks/on-push", trigger.text("webhookPath"))
        assertEquals("true", trigger.text("secretConfigured"))
        assertNotNull(trigger["secretRotatedAt"])
        val hashOfSecret = WebhookSecrets.hash(secret)
        listOf("/api/v1/triggers", "/api/v1/triggers/on-push", "/api/v1/triggers/on-push/firings")
            .forEach {
              val text = send(HttpMethod.Get, it, TestTokens.ROOT).bodyAsText()
              assertFalse(text.contains(secret), it)
              assertFalse(text.contains(hashOfSecret), it)
              assertFalse(text.contains("\"secret\""), it)
            }
        // Updating, enabling or disabling does not show it either.
        val patched =
            send(
                    HttpMethod.Patch,
                    "/api/v1/triggers/on-push",
                    TestTokens.ROOT,
                    """{"enabled":false}""",
                )
                .bodyAsText()
        assertFalse(patched.contains(secret))
        assertFalse(patched.contains(hashOfSecret))
      }

  @Test
  fun `triggers are listed by name`() = testApplication {
    engine()
    val hash = uploaded("nightly")
    createTrigger(cronBody("b", hash, "nightly"))
    createTrigger(hookBody("a", hash, "nightly"))

    val list = send(HttpMethod.Get, "/api/v1/triggers", TestTokens.ROOT).json().array("triggers")

    assertEquals(listOf("a", "b"), list.map { it.text("name") })
    assertEquals(
        HttpStatusCode.NotFound,
        send(HttpMethod.Get, "/api/v1/triggers/none", TestTokens.ROOT).status,
    )
    assertEquals(
        "trigger_not_found",
        send(HttpMethod.Get, "/api/v1/triggers/none", TestTokens.ROOT).json().text("error"),
    )
  }

  @Test
  fun `a request that is refused says why and creates nothing`() = testApplication {
    engine()
    val hash = uploaded("nightly")
    createTrigger(cronBody("taken", hash, "nightly"))

    fun problem(r: JsonObject) = r["problem"]?.jsonPrimitive?.content

    val missing = createTrigger(cronBody("x", hash, "nightly", params = "{}"))
    assertEquals(HttpStatusCode.UnprocessableEntity, missing.status)
    assertEquals("invalid_parameters", missing.json().text("error"))
    assertEquals("env", missing.json().array("problems").single().text("name"))
    assertEquals("missing", missing.json().array("problems").single().text("problem"))

    val undeclared =
        createTrigger(cronBody("x", hash, "nightly", params = """{"env":"p","nope":"1"}"""))
    assertEquals(HttpStatusCode.UnprocessableEntity, undeclared.status)
    assertEquals("nope", undeclared.json().array("problems").single().text("name"))

    val badCron = createTrigger(cronBody("x", hash, "nightly").replace("0 2 * * *", "every day"))
    assertEquals(HttpStatusCode.UnprocessableEntity, badCron.status)
    assertEquals("invalid_trigger", badCron.json().text("error"))
    assertEquals("cron_expression", problem(badCron.json()))

    val badZone =
        createTrigger(cronBody("x", hash, "nightly", extra = ""","timeZone":"Mars/Olympus""""))
    assertEquals(HttpStatusCode.UnprocessableEntity, badZone.status)
    assertEquals("time_zone", problem(badZone.json()))

    val noCron =
        createTrigger(
            """{"name":"x","kind":"cron","contentHash":"$hash","pipeline":"nightly","parameters":{"env":"p"}}"""
        )
    assertEquals(HttpStatusCode.UnprocessableEntity, noCron.status)
    assertEquals("cron_required", problem(noCron.json()))

    val hookWithCron =
        createTrigger(hookBody("x", hash, "nightly", extra = ""","cron":"0 2 * * *""""))
    assertEquals("schedule_not_allowed", problem(hookWithCron.json()))

    val badName = createTrigger(cronBody("bad name", hash, "nightly"))
    assertEquals(HttpStatusCode.UnprocessableEntity, badName.status)
    assertEquals("name", problem(badName.json()))

    val noDefinition = createTrigger(cronBody("x", "0".repeat(64), "nightly"))
    assertEquals(HttpStatusCode.NotFound, noDefinition.status)
    assertEquals("definition_not_found", noDefinition.json().text("error"))

    val exists = createTrigger(cronBody("taken", hash, "nightly"))
    assertEquals(HttpStatusCode.Conflict, exists.status)
    assertEquals("trigger_exists", exists.json().text("error"))

    assertEquals(HttpStatusCode.BadRequest, createTrigger("not json").status)
    assertEquals(
        HttpStatusCode.BadRequest,
        createTrigger(cronBody("x", hash, "nightly").replace("\"cron\"", "\"kind2\"")).status,
    )
    assertEquals(
        HttpStatusCode.BadRequest,
        createTrigger(
                cronBody("x", hash, "nightly").replace("\"kind\":\"cron\"", "\"kind\":\"timer\"")
            )
            .status,
    )

    assertEquals(
        listOf("taken"),
        send(HttpMethod.Get, "/api/v1/triggers", TestTokens.ROOT).json().array("triggers").map {
          it.text("name")
        },
    )
  }

  // ---- changing triggers ----

  @Test
  fun `a trigger is changed, disabled and enabled by an administrator whose name is recorded`() =
      testApplication {
        engine()
        val hash = uploaded("nightly")
        createTrigger(cronBody("t", hash, "nightly"))

        val changed =
            send(
                HttpMethod.Patch,
                "/api/v1/triggers/t",
                ops,
                """{"parameters":{"env":"stage"},"cron":"30 4 * * 1","timeZone":"UTC","enabled":false}""",
            )

        assertEquals(HttpStatusCode.OK, changed.status, changed.bodyAsText())
        val trigger = changed.json()
        assertEquals("stage", trigger.obj("parameters").text("env"))
        assertEquals("30 4 * * 1", trigger.text("cron"))
        assertEquals("UTC", trigger.text("timeZone"))
        assertEquals("false", trigger.text("enabled"))
        assertEquals("ops", trigger.text("updatedBy"))
        assertEquals("root", trigger.text("createdBy"))
        assertEquals(
            "true",
            send(HttpMethod.Patch, "/api/v1/triggers/t", ops, """{"enabled":true}""")
                .json()
                .text("enabled"),
        )
      }

  @Test
  fun `a change that is refused leaves the trigger as it was`() = testApplication {
    engine()
    val hash = uploaded("nightly")
    createTrigger(cronBody("t", hash, "nightly"))
    val v2 =
        uploaded(
            "nightly",
            declaration =
                RunHarness.DEFAULT_DECLARATION +
                    ", parameters = {@Param(name = \"env\"), @Param(name = \"region\")}",
        )

    val rebind =
        send(HttpMethod.Patch, "/api/v1/triggers/t", TestTokens.ROOT, """{"contentHash":"$v2"}""")
    assertEquals(HttpStatusCode.UnprocessableEntity, rebind.status)
    assertEquals("region", rebind.json().array("problems").single().text("name"))
    assertEquals(
        hash,
        send(HttpMethod.Get, "/api/v1/triggers/t", TestTokens.ROOT).json().text("contentHash"),
    )

    val ok =
        send(
            HttpMethod.Patch,
            "/api/v1/triggers/t",
            TestTokens.ROOT,
            """{"contentHash":"$v2","parameters":{"env":"p","region":"eu"}}""",
        )
    assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
    assertEquals(v2, ok.json().text("contentHash"))

    assertEquals(
        HttpStatusCode.UnprocessableEntity,
        send(HttpMethod.Patch, "/api/v1/triggers/t", TestTokens.ROOT, "{}").status,
    )
    assertEquals(
        HttpStatusCode.UnprocessableEntity,
        send(HttpMethod.Patch, "/api/v1/triggers/t", TestTokens.ROOT, """{"cron":"nope"}""").status,
    )
    assertEquals(
        HttpStatusCode.NotFound,
        send(
                HttpMethod.Patch,
                "/api/v1/triggers/t",
                TestTokens.ROOT,
                """{"contentHash":"${"0".repeat(64)}"}""",
            )
            .status,
    )
    assertEquals(
        HttpStatusCode.NotFound,
        send(HttpMethod.Patch, "/api/v1/triggers/none", TestTokens.ROOT, """{"enabled":true}""")
            .status,
    )
    assertEquals(
        HttpStatusCode.BadRequest,
        send(HttpMethod.Patch, "/api/v1/triggers/t", TestTokens.ROOT, "oops").status,
    )
  }

  @Test
  fun `a binding does not follow the latest version`() = testApplication {
    engine()
    val v1 = uploaded("nightly")
    createTrigger(cronBody("t", v1, "nightly"))

    uploaded("nightly")

    assertEquals(
        v1,
        send(HttpMethod.Get, "/api/v1/triggers/t", TestTokens.ROOT).json().text("contentHash"),
    )
  }

  // ---- binding and deleting artifacts ----

  @Test
  fun `an artifact a trigger is bound to cannot be deleted until the trigger is deleted`() =
      testApplication {
        engine()
        val hash = uploaded("nightly")
        createTrigger(cronBody("t", hash, "nightly"))

        val refused = send(HttpMethod.Delete, "/api/v1/artifacts/$hash", TestTokens.ROOT)
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals("in_use", refused.json().text("error"))

        assertEquals(
            HttpStatusCode.NoContent,
            send(HttpMethod.Delete, "/api/v1/triggers/t", TestTokens.ROOT).status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            send(HttpMethod.Get, "/api/v1/triggers/t", TestTokens.ROOT).status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            send(HttpMethod.Delete, "/api/v1/triggers/t", TestTokens.ROOT).status,
        )

        assertEquals(
            HttpStatusCode.NoContent,
            send(HttpMethod.Delete, "/api/v1/artifacts/$hash", TestTokens.ROOT).status,
        )
      }

  // ---- the webhook entry ----

  @Test
  fun `a webhook call with the secret and a delivery identifier is accepted and creates a run`() =
      testApplication {
        engine()
        val hash = uploaded("nightly")
        val secret = createTrigger(hookBody("on-push", hash, "nightly")).json().text("secret")

        val response = webhook("on-push", secret, "delivery-1")

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertEquals("""{"status":"accepted"}""", response.bodyAsText())
        val run = awaitRuns(1).single()
        assertEquals("SUCCEEDED", run.text("state"))
        assertEquals("TRIGGER", run.obj("source").text("kind"))
        assertEquals("on-push", run.obj("source").text("name"))
        assertEquals("prod", run.obj("parameters").text("env"))
      }

  @Test
  fun `every failed authentication gets the same answer, whatever the reason`() = testApplication {
    engine()
    val hash = uploaded("nightly")
    val secret = createTrigger(hookBody("on-push", hash, "nightly")).json().text("secret")
    val disabledSecret =
        createTrigger(hookBody("off", hash, "nightly", extra = ""","enabled":false"""))
            .json()
            .text("secret")
    createTrigger(cronBody("a-cron", hash, "nightly"))

    val answers =
        listOf(
            webhook("on-push", "wrong", "d-1"),
            webhook("on-push", null, "d-1"),
            webhook("on-push", "", "d-1"),
            webhook("nobody", secret, "d-1"),
            webhook("off", disabledSecret, "d-1"),
            webhook("a-cron", secret, "d-1"),
            webhook("on-push", "wrong", null),
        )

    answers.forEach { assertEquals(HttpStatusCode.Unauthorized, it.status) }
    assertEquals(1, answers.map { it.bodyAsText() }.toSet().size, "one body for every reason")
    assertEquals(
        1,
        answers
            .map { it.headers.entries().filter { h -> h.key !in IGNORED_HEADERS }.toSet() }
            .toSet()
            .size,
    )
    assertFalse(answers.first().bodyAsText().contains("on-push"))
    assertEquals(0, runs().size)
  }

  @Test
  fun `an authenticated call without a usable delivery identifier is refused`() = testApplication {
    engine()
    val hash = uploaded("nightly")
    val secret = createTrigger(hookBody("on-push", hash, "nightly")).json().text("secret")

    val missing = webhook("on-push", secret, null)
    assertEquals(HttpStatusCode.BadRequest, missing.status)
    assertEquals("invalid_delivery_id", missing.json().text("error"))
    assertEquals(HttpStatusCode.BadRequest, webhook("on-push", secret, "").status)
    assertEquals(HttpStatusCode.BadRequest, webhook("on-push", secret, "x".repeat(201)).status)
    assertEquals(0, runs().size)
  }

  @Test
  fun `the request body is not read and cannot change the parameters`() = testApplication {
    engine()
    val hash = uploaded("nightly")
    val secret = createTrigger(hookBody("on-push", hash, "nightly")).json().text("secret")

    val json = webhook("on-push", secret, "d-1", """{"parameters":{"env":"evil"},"env":"evil"}""")
    val garbage = webhook("on-push", secret, "d-2", "\u0000not json at all {{{")

    assertEquals(HttpStatusCode.Accepted, json.status)
    assertEquals(HttpStatusCode.Accepted, garbage.status)
    val all = awaitRuns(2)
    assertEquals(setOf("prod"), all.map { it.obj("parameters").text("env") }.toSet())
  }

  @Test
  fun `a repeated delivery gets the same answer as the first and creates no second run`() =
      testApplication {
        engine()
        val hash = uploaded("nightly")
        val secret = createTrigger(hookBody("on-push", hash, "nightly")).json().text("secret")

        val first = webhook("on-push", secret, "d-1")
        val again = webhook("on-push", secret, "d-1")

        assertEquals(HttpStatusCode.Accepted, again.status)
        assertEquals(first.bodyAsText(), again.bodyAsText())
        assertEquals(
            first.headers.entries().filter { it.key !in IGNORED_HEADERS }.toSet(),
            again.headers.entries().filter { it.key !in IGNORED_HEADERS }.toSet(),
        )
        assertEquals(1, awaitRuns(1).size)
      }

  @Test
  fun `a delivery sent many times at once creates one run and every call is accepted`() =
      testApplication {
        engine()
        val hash = uploaded("nightly")
        val secret = createTrigger(hookBody("on-push", hash, "nightly")).json().text("secret")

        val statuses = coroutineScope {
          (1..20).map { async { webhook("on-push", secret, "racing").status } }.awaitAll()
        }

        assertEquals(List(20) { HttpStatusCode.Accepted }, statuses)
        assertEquals(1, awaitRuns(1).size)
        val firings =
            send(HttpMethod.Get, "/api/v1/triggers/on-push/firings", TestTokens.ROOT)
                .json()
                .array("firings")
        assertEquals(1, firings.size)
      }

  @Test
  fun `a rotated secret replaces the old one at once and is shown once`() = testApplication {
    engine()
    val hash = uploaded("nightly")
    val first = createTrigger(hookBody("on-push", hash, "nightly")).json().text("secret")

    val rotated = send(HttpMethod.Post, "/api/v1/triggers/on-push/rotate-secret", ops)
    assertEquals(HttpStatusCode.OK, rotated.status, rotated.bodyAsText())
    val second = rotated.json().text("secret")
    assertNotEquals(first, second)
    assertEquals("ops", rotated.json().obj("trigger").text("updatedBy"))

    assertEquals(HttpStatusCode.Unauthorized, webhook("on-push", first, "d-1").status)
    assertEquals(HttpStatusCode.Accepted, webhook("on-push", second, "d-1").status)
    assertFalse(
        send(HttpMethod.Get, "/api/v1/triggers/on-push", TestTokens.ROOT)
            .bodyAsText()
            .contains(second)
    )
  }

  @Test
  fun `only a webhook trigger has a secret to rotate`() = testApplication {
    engine()
    val hash = uploaded("nightly")
    createTrigger(cronBody("c", hash, "nightly"))

    val refused = send(HttpMethod.Post, "/api/v1/triggers/c/rotate-secret", TestTokens.ROOT)
    assertEquals(HttpStatusCode.Conflict, refused.status)
    assertEquals("not_a_webhook", refused.json().text("error"))
    assertEquals(
        HttpStatusCode.NotFound,
        send(HttpMethod.Post, "/api/v1/triggers/none/rotate-secret", TestTokens.ROOT).status,
    )
  }

  @Test
  fun `a disabled webhook trigger creates no run until it is enabled`() = testApplication {
    engine()
    val hash = uploaded("nightly")
    val secret = createTrigger(hookBody("on-push", hash, "nightly")).json().text("secret")
    send(HttpMethod.Patch, "/api/v1/triggers/on-push", TestTokens.ROOT, """{"enabled":false}""")

    assertEquals(HttpStatusCode.Unauthorized, webhook("on-push", secret, "d-1").status)
    assertEquals(0, runs().size)

    send(HttpMethod.Patch, "/api/v1/triggers/on-push", TestTokens.ROOT, """{"enabled":true}""")
    assertEquals(HttpStatusCode.Accepted, webhook("on-push", secret, "d-1").status)
    assertEquals(1, awaitRuns(1).size)
  }

  // ---- the record of firings ----

  @Test
  fun `an administrator sees the recent firings with their results, reasons and runs`() =
      testApplication {
        engine()
        val safe = uploaded("nightly")
        val risky =
            uploaded(
                "risky",
                body = "java.nio.file.Files.exists(java.nio.file.Path.of(\"x\"));",
                declaration =
                    RunHarness.DEFAULT_DECLARATION + ", parameters = {@Param(name = \"env\")}",
            )
        val good = createTrigger(hookBody("good", safe, "nightly")).json().text("secret")
        val bad = createTrigger(hookBody("bad", risky, "risky")).json().text("secret")

        webhook("good", good, "g-1")
        webhook("good", good, "g-2")
        webhook("good", good, "g-1")
        webhook("bad", bad, "b-1")
        val runIds = awaitRuns(2).map { it.text("runId") }.toSet()

        val goodFirings =
            send(HttpMethod.Get, "/api/v1/triggers/good/firings", TestTokens.ROOT)
                .json()
                .array("firings")
        assertEquals(listOf("g-2", "g-1"), goodFirings.map { it.text("deliveryId") })
        assertTrue(goodFirings.all { it.text("outcome") == "run_created" })
        assertEquals(runIds, goodFirings.map { it.text("runId") }.toSet())
        assertNotNull(goodFirings.first()["firedAt"])

        val badFiring =
            send(HttpMethod.Get, "/api/v1/triggers/bad/firings", TestTokens.ROOT)
                .json()
                .array("firings")
                .single()
        assertEquals("refused", badFiring.text("outcome"))
        assertEquals("unsafe_not_allowed", badFiring.text("reason"))
        assertTrue(badFiring.text("detail").isNotEmpty())
        assertEquals(JsonNull, badFiring["runId"])

        val limited =
            send(HttpMethod.Get, "/api/v1/triggers/good/firings?limit=1", TestTokens.ROOT)
                .json()
                .array("firings")
        assertEquals(1, limited.size)
        assertEquals(
            HttpStatusCode.NotFound,
            send(HttpMethod.Get, "/api/v1/triggers/none/firings", TestTokens.ROOT).status,
        )
      }

  // ---- no secret in logs ----

  @Test
  fun `neither a webhook secret nor an API token appears in the logs`() = testApplication {
    engine()
    CapturedLogs().use { logs ->
      val hash = uploaded("nightly")
      val secret = createTrigger(hookBody("on-push", hash, "nightly")).json().text("secret")
      val rotated =
          send(HttpMethod.Post, "/api/v1/triggers/on-push/rotate-secret", TestTokens.ROOT)
              .json()
              .text("secret")
      val wrong = "attempted-secret-0123456789abcdefghijklmnop"
      val wrongToken = "attempted-token-0123456789abcdefghijklmnop"
      webhook("on-push", secret, "d-1")
      webhook("on-push", rotated, "d-2")
      webhook("on-push", wrong, "d-3")
      send(HttpMethod.Get, "/api/v1/triggers", wrongToken)
      send(HttpMethod.Get, "/api/v1/triggers", TestTokens.ALICE)
      awaitRuns(1)

      val text = logs.lines.joinToString("\n")
      listOf(
              secret,
              rotated,
              wrong,
              wrongToken,
              TestTokens.ROOT,
              TestTokens.ALICE,
              TestTokens.BOB,
              ops,
              WebhookSecrets.hash(secret),
              WebhookSecrets.hash(rotated),
          )
          .forEach { assertFalse(text.contains(it), "a secret is in the logs") }
      assertTrue(text.contains("on-push"), "the test must see real log lines")
    }
  }

  private companion object {
    // Headers that legitimately differ between two responses, such as the time.
    val IGNORED_HEADERS = setOf(HttpHeaders.Date, HttpHeaders.ContentLength)
  }
}
