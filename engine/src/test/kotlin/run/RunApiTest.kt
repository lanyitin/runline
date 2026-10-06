package dev.lawlan.runline.engine.run

import ch.qos.logback.classic.Logger
import dev.lawlan.runline.engine.artifact.PostgresArtifactStore
import dev.lawlan.runline.engine.artifact.PostgresDefinitionStore
import dev.lawlan.runline.engine.artifact.Visibility
import dev.lawlan.runline.engine.config.DatabaseConfig
import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.support.PipelineJars
import dev.lawlan.runline.engine.support.RunHarness
import dev.lawlan.runline.engine.support.SnapshotListAppender
import dev.lawlan.runline.engine.support.StoredPipelines
import dev.lawlan.runline.engine.support.TestTokens
import dev.lawlan.runline.engine.support.configureEngine
import dev.lawlan.runline.engine.support.migratedDatabase
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.ktor.websocket.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

class RunApiTest {
  private val dir: Path = Files.createTempDirectory("run-api")
  private val database: DatabaseConfig = migratedDatabase()
  private val safeList = "java.lang,java.util,java.io,kotlin,org.jetbrains.annotations"
  private var counter = 0

  private fun ApplicationTestBuilder.engine(vararg overrides: Pair<String, String>) =
      configureEngine(database, mapOf("analysis.allowlist" to safeList) + overrides)

  private fun bearer(token: String?): HttpRequestBuilder.() -> Unit = {
    token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
  }

  private suspend fun HttpResponse.json(): JsonObject =
      Json.parseToJsonElement(bodyAsText()).jsonObject

  private fun JsonObject.text(key: String) = this[key]!!.jsonPrimitive.content

  /** Uploads a compiled pipeline as [token]'s owner; returns its content hash. */
  private suspend fun ApplicationTestBuilder.upload(
      name: String,
      body: String = "",
      token: String = TestTokens.ALICE,
      declaration: String = RunHarness.DEFAULT_DECLARATION,
  ): String {
    val fqcn = "demo.A${counter++}"
    val jar =
        PipelineJars.build(
            dir,
            "j-${System.nanoTime()}.jar",
            mapOf(fqcn to PipelineJars.pipeline(fqcn, name, declaration, "", body)),
        )
    val response =
        client.post("/api/v1/artifacts") {
          bearer(token)()
          contentType(ContentType.Application.OctetStream)
          setBody(Files.readAllBytes(jar))
        }
    assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
    return response.json().text("contentHash")
  }

  private suspend fun ApplicationTestBuilder.createRun(
      hash: String,
      pipeline: String,
      parameters: Map<String, String> = emptyMap(),
      token: String? = TestTokens.ALICE,
  ): HttpResponse =
      client.post("/api/v1/runs") {
        bearer(token)()
        contentType(ContentType.Application.Json)
        setBody(
            buildJsonObject {
              put("contentHash", hash)
              put("pipeline", pipeline)
              put("parameters", JsonObject(parameters.mapValues { JsonPrimitive(it.value) }))
            }
                .toString()
        )
      }

  private suspend fun ApplicationTestBuilder.get(path: String, token: String?): HttpResponse =
      client.get(path) { bearer(token)() }

  private suspend fun ApplicationTestBuilder.runState(id: String, token: String = TestTokens.ROOT) =
      get("/api/v1/runs/$id", token).json().text("state")

  private suspend fun ApplicationTestBuilder.awaitState(
      id: String,
      vararg states: String,
      token: String = TestTokens.ROOT,
  ): JsonObject {
    val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
    while (System.nanoTime() < deadline) {
      val run = get("/api/v1/runs/$id", token).json()
      if (run.text("state") in states) return run
      kotlinx.coroutines.delay(25)
    }
    fail("run $id is ${runState(id)}, expected one of ${states.toList()}")
  }

  private suspend fun ApplicationTestBuilder.start(
      hash: String,
      pipeline: String,
      parameters: Map<String, String> = emptyMap(),
      token: String = TestTokens.ALICE,
  ): String {
    val response = createRun(hash, pipeline, parameters, token)
    assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
    return response.json().text("runId")
  }

  private suspend fun ApplicationTestBuilder.allowUnsafe(hash: String, pipeline: String) =
      client.put("/api/v1/definitions/$hash/$pipeline/unsafe-execution") {
        bearer(TestTokens.ROOT)()
        contentType(ContentType.Application.Json)
        setBody("""{"allow":true}""")
      }

  private val unsafeBody = "java.nio.file.Files.exists(java.nio.file.Path.of(\"x\"));"

  private fun runCount(): Long =
      dataSourceOf(database).connection.use { c ->
        c.createStatement().use { s ->
          s.executeQuery("SELECT count(*) FROM run").use {
            it.next()
            it.getLong(1)
          }
        }
      }

  // ---- authentication and authorization ----

  @Test
  fun `every run endpoint requires authentication`() = testApplication {
    engine()
    val id = "00000000-0000-0000-0000-000000000001"

    assertEquals(HttpStatusCode.Unauthorized, createRun("a".repeat(64), "p", token = null).status)
    assertEquals(
        HttpStatusCode.Unauthorized,
        createRun("a".repeat(64), "p", token = "wrong").status,
    )
    assertEquals(HttpStatusCode.Unauthorized, get("/api/v1/runs", null).status)
    assertEquals(HttpStatusCode.Unauthorized, get("/api/v1/runs/$id", null).status)
    assertEquals(HttpStatusCode.Unauthorized, get("/api/v1/runs/$id/log", null).status)
    assertEquals(
        HttpStatusCode.Unauthorized,
        client.post("/api/v1/runs/$id/cancel").status,
    )
    assertEquals(0, runCount())
  }

  // ---- creating ----

  @Test
  fun `a developer runs their own pipeline and follows it to the end`() = testApplication {
    engine()
    val hash = upload("hello", """System.out.println("hello");""")

    val response = createRun(hash, "hello")

    assertEquals(HttpStatusCode.Created, response.status)
    val created = response.json()
    val id = created.text("runId")
    assertEquals("QUEUED", created.text("state"))
    assertEquals("/api/v1/runs/$id", response.headers[HttpHeaders.Location])
    val run = awaitState(id, "SUCCEEDED")
    assertEquals(hash, run.text("contentHash"))
    assertEquals("hello", run.text("pipeline"))
    assertEquals("MANUAL", run["source"]!!.jsonObject.text("kind"))
    assertEquals("alice", run["source"]!!.jsonObject.text("name"))
    assertNotNull(run["startedAt"])
    assertNotNull(run["finishedAt"])
    assertEquals(JsonNull, run["failure"])
    assertEquals(JsonNull, run["unsafeExecution"])
  }

  @Test
  fun `a developer can run any version they uploaded, an older one included`() = testApplication {
    engine()
    val old = upload("tool", """System.out.println("v1");""")
    val new = upload("tool", """System.out.println("v2");""")

    val first = start(old, "tool")
    val second = start(new, "tool")

    awaitState(first, "SUCCEEDED")
    awaitState(second, "SUCCEEDED")
    val log = get("/api/v1/runs/$first/log", TestTokens.ALICE).json()["entries"]!!.jsonArray
    assertEquals("v1", log.single().jsonObject.text("line"))
  }

  @Test
  fun `another developer cannot run it and is told it does not exist, as for a missing one`() =
      testApplication {
        engine()
        val hash = upload("private-tool")

        val hidden = createRun(hash, "private-tool", token = TestTokens.BOB)
        val missing = createRun("f".repeat(64), "private-tool", token = TestTokens.BOB)

        assertEquals(HttpStatusCode.NotFound, hidden.status)
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals(missing.json(), hidden.json())
        assertEquals("definition_not_found", hidden.json().text("error"))
        assertEquals(0, runCount())
      }

  @Test
  fun `an administrator can run any pipeline`() = testApplication {
    engine()
    val hash = upload("private-tool")

    val id = start(hash, "private-tool", token = TestTokens.ROOT)

    awaitState(id, "SUCCEEDED")
  }

  @Test
  fun `parameters that do not match the declaration are refused naming each parameter`() =
      testApplication {
        engine()
        val hash =
            upload(
                "needs",
                declaration =
                    RunHarness.DEFAULT_DECLARATION + ", parameters = {@Param(name = \"env\")}",
            )

        val response = createRun(hash, "needs", mapOf("colour" to "red"))

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        val body = response.json()
        assertEquals("invalid_parameters", body.text("error"))
        val problems =
            body["problems"]!!.jsonArray.map {
              it.jsonObject.text("name") to it.jsonObject.text("problem")
            }
        assertEquals(listOf("env" to "missing", "colour" to "undeclared"), problems)
        assertTrue(body.text("message").contains("env") && body.text("message").contains("colour"))
        assertEquals(0, runCount())
      }

  @Test
  fun `an unsafe pipeline is refused until an administrator allows it`() = testApplication {
    engine()
    val hash = upload("risky", unsafeBody)

    val refused = createRun(hash, "risky")

    assertEquals(HttpStatusCode.Conflict, refused.status)
    assertEquals("unsafe_not_allowed", refused.json().text("error"))
    assertEquals(0, runCount())

    assertEquals(HttpStatusCode.OK, allowUnsafe(hash, "risky").status)
    val id = start(hash, "risky")
    val run = awaitState(id, "SUCCEEDED")
    val setting = run["unsafeExecution"]!!.jsonObject
    assertEquals("root", setting.text("setBy"))
    assertNotNull(setting["setAt"])
  }

  @Test
  fun `each rejection has its own error code`() = testApplication {
    engine()
    val hash = upload("risky", unsafeBody)

    val codes =
        listOf(
            createRun("f".repeat(64), "risky").json().text("error"),
            createRun(hash, "risky", mapOf("x" to "1")).json().text("error"),
            createRun(hash, "risky").json().text("error"),
        )

    assertEquals(
        listOf("definition_not_found", "invalid_parameters", "unsafe_not_allowed"),
        codes,
    )
  }

  @Test
  fun `a request body that cannot be understood is a bad request`() = testApplication {
    engine()

    for (body in listOf("not json", "{}", """{"contentHash":"x"}""")) {
      val response =
          client.post("/api/v1/runs") {
            bearer(TestTokens.ALICE)()
            contentType(ContentType.Application.Json)
            setBody(body)
          }
      assertEquals(HttpStatusCode.BadRequest, response.status, body)
      assertEquals("bad_request", response.json().text("error"))
    }
    assertEquals(0, runCount())
  }

  // ---- querying ----

  @Test
  fun `a run is visible to its owner and administrators but looks absent to other developers`() =
      testApplication {
        engine()
        val hash = upload("mine")
        val id = start(hash, "mine")
        awaitState(id, "SUCCEEDED")

        assertEquals(HttpStatusCode.OK, get("/api/v1/runs/$id", TestTokens.ALICE).status)
        assertEquals(HttpStatusCode.OK, get("/api/v1/runs/$id", TestTokens.ROOT).status)
        val hidden = get("/api/v1/runs/$id", TestTokens.BOB)
        val missing = get("/api/v1/runs/00000000-0000-0000-0000-000000000009", TestTokens.BOB)
        assertEquals(HttpStatusCode.NotFound, hidden.status)
        assertEquals(missing.json().text("error"), hidden.json().text("error"))
        assertEquals(
            HttpStatusCode.NotFound,
            get("/api/v1/runs/not-a-uuid", TestTokens.ALICE).status,
        )
      }

  @Test
  fun `listing shows a developer their own runs and an administrator all of them`() =
      testApplication {
        engine()
        val mine = upload("mine")
        val theirs = upload("theirs", token = TestTokens.BOB)
        val a = start(mine, "mine")
        val b = start(theirs, "theirs", token = TestTokens.BOB)
        awaitState(a, "SUCCEEDED")
        awaitState(b, "SUCCEEDED")

        fun ids(response: HttpResponse) = runBlocking {
          response.json()["runs"]!!.jsonArray.map { it.jsonObject.text("runId") }
        }

        assertEquals(listOf(a), ids(get("/api/v1/runs", TestTokens.ALICE)))
        assertEquals(listOf(b), ids(get("/api/v1/runs", TestTokens.BOB)))
        assertEquals(listOf(b, a), ids(get("/api/v1/runs", TestTokens.ROOT)))
        assertEquals(listOf(a), ids(get("/api/v1/runs?pipeline=mine", TestTokens.ROOT)))
        assertEquals(listOf(b), ids(get("/api/v1/runs?limit=1", TestTokens.ROOT)))
      }

  @Test
  fun `a run that fails shows its reason`() = testApplication {
    engine()
    val hash = upload("bad", """throw new IllegalStateException("boom");""")

    val run = awaitState(start(hash, "bad"), "FAILED")

    val failure = run["failure"]!!.jsonObject
    assertEquals("java.lang.IllegalStateException", failure.text("type"))
    assertEquals("boom", failure.text("message"))
    assertTrue(failure.text("trace").contains("boom"))
  }

  @Test
  fun `the log can be read in pieces and only by those who may see the run`() = testApplication {
    engine()
    val hash =
        upload(
            "talk",
            """
            System.out.println("one");
            System.err.println("two");
            System.out.println("three");
            """
                .trimIndent(),
        )
    val id = start(hash, "talk")
    awaitState(id, "SUCCEEDED")

    val all = get("/api/v1/runs/$id/log", TestTokens.ALICE).json()
    val entries = all["entries"]!!.jsonArray.map { it.jsonObject }
    assertEquals(listOf("one", "two", "three"), entries.map { it.text("line") })
    assertEquals(listOf("STDOUT", "STDERR", "STDOUT"), entries.map { it.text("stream") })
    assertEquals(listOf(1L, 2L, 3L), entries.map { it["seq"]!!.jsonPrimitive.long })

    val rest = get("/api/v1/runs/$id/log?after=1&limit=1", TestTokens.ALICE).json()
    assertEquals(listOf("two"), rest["entries"]!!.jsonArray.map { it.jsonObject.text("line") })
    assertEquals(2L, rest["last"]!!.jsonPrimitive.long)

    assertEquals(HttpStatusCode.NotFound, get("/api/v1/runs/$id/log", TestTokens.BOB).status)
  }

  // ---- cancelling ----

  @Test
  fun `a running run is cancelled on request and ends cancelled`() = testApplication {
    engine()
    val hash = upload("waits", RunHarness.WAIT_FOR_STOP)
    val id = start(hash, "waits")
    awaitState(id, "RUNNING")

    val response = client.post("/api/v1/runs/$id/cancel") { bearer(TestTokens.ALICE)() }

    assertEquals(HttpStatusCode.Accepted, response.status)
    awaitState(id, "CANCELLED")
  }

  @Test
  fun `a run that has not started is cancelled at once`() = testApplication {
    engine("runs.maxConcurrent" to "1")
    val blocker = upload("blocker", RunHarness.WAIT_FOR_STOP)
    val other = upload("other")
    val first = start(blocker, "blocker")
    awaitState(first, "RUNNING")
    val queued = start(other, "other")

    val response = client.post("/api/v1/runs/$queued/cancel") { bearer(TestTokens.ALICE)() }

    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals("CANCELLED", response.json().text("state"))
    assertEquals("CANCELLED", runState(queued))
    client.post("/api/v1/runs/$first/cancel") { bearer(TestTokens.ALICE)() }
  }

  @Test
  fun `cancelling is limited to the owner and administrators, and a finished run cannot be cancelled`() =
      testApplication {
        engine()
        val hash = upload("waits", RunHarness.WAIT_FOR_STOP)
        val id = start(hash, "waits")
        awaitState(id, "RUNNING")

        val other = client.post("/api/v1/runs/$id/cancel") { bearer(TestTokens.BOB)() }
        assertEquals(HttpStatusCode.NotFound, other.status)
        assertEquals("RUNNING", runState(id))

        val byAdmin = client.post("/api/v1/runs/$id/cancel") { bearer(TestTokens.ROOT)() }
        assertEquals(HttpStatusCode.Accepted, byAdmin.status)
        awaitState(id, "CANCELLED")

        val again = client.post("/api/v1/runs/$id/cancel") { bearer(TestTokens.ALICE)() }
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertEquals("already_finished", again.json().text("error"))
        assertEquals(
            HttpStatusCode.NotFound,
            client
                .post("/api/v1/runs/00000000-0000-0000-0000-000000000009/cancel") {
                  bearer(TestTokens.ALICE)()
                }
                .status,
        )
      }

  // ---- the unsafe setting ----

  @Test
  fun `only an administrator may change the unsafe setting, recorded under their name`() =
      testApplication {
        engine()
        val hash = upload("risky", unsafeBody)
        fun put(token: String?) = runBlocking {
          client.put("/api/v1/definitions/$hash/risky/unsafe-execution") {
            bearer(token)()
            contentType(ContentType.Application.Json)
            setBody("""{"allow":true}""")
          }
        }

        assertEquals(HttpStatusCode.Unauthorized, put(null).status)
        assertEquals(HttpStatusCode.Forbidden, put(TestTokens.ALICE).status)
        assertEquals(false, definitionAllowsUnsafe(hash))

        val response = put(TestTokens.ROOT)

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.json()
        assertEquals(true, body["allow"]!!.jsonPrimitive.boolean)
        assertEquals("root", body.text("setBy"))
        assertNotNull(body["setAt"])
        assertEquals(true, definitionAllowsUnsafe(hash))
      }

  @Test
  fun `the unsafe setting can be withdrawn and an unknown definition is not found`() =
      testApplication {
        engine()
        val hash = upload("risky", unsafeBody)
        allowUnsafe(hash, "risky")

        val withdrawn =
            client.put("/api/v1/definitions/$hash/risky/unsafe-execution") {
              bearer(TestTokens.ROOT)()
              contentType(ContentType.Application.Json)
              setBody("""{"allow":false}""")
            }
        assertEquals(false, withdrawn.json()["allow"]!!.jsonPrimitive.boolean)
        assertEquals(HttpStatusCode.Conflict, createRun(hash, "risky").status)

        assertEquals(HttpStatusCode.NotFound, allowUnsafe(hash, "nothing").status)
        assertEquals(HttpStatusCode.NotFound, allowUnsafe("f".repeat(64), "risky").status)
      }

  private fun definitionAllowsUnsafe(hash: String): Boolean =
      dataSourceOf(database).connection.use { c ->
        c.prepareStatement(
                "SELECT d.allow_unsafe_execution FROM pipeline_definition d " +
                    "JOIN pipeline_artifact a ON a.id = d.artifact_id WHERE a.content_hash = ?"
            )
            .use {
              it.setString(1, hash)
              it.executeQuery().use { rs ->
                rs.next()
                rs.getBoolean(1)
              }
            }
      }

  // ---- log streaming ----

  @Test
  fun `a log stream delivers lines as they are written and ends when the run ends`() =
      testApplication {
        val shared = Files.createTempDirectory(dir, "shared")
        engine("workspace.sharedRoot" to shared.toString())
        val marker = shared.resolve("chatty").resolve("release")
        val hash =
            upload(
                "chatty",
                """
                System.out.println("first");
                try {
                  while (!context.getFiles().exists(FileScope.PIPELINE_SHARED, "release")) Thread.sleep(10);
                } catch (InterruptedException e) { throw new RuntimeException(e); }
                System.out.println("second");
                """
                    .trimIndent(),
            )
        val id = start(hash, "chatty")
        val wsClient = createClient { install(WebSockets) }

        val lines = mutableListOf<String>()
        wsClient.webSocket("/api/v1/runs/$id/log/stream", request = bearer(TestTokens.ALICE)) {
          withTimeout(30_000) {
            val first = incoming.receive() as Frame.Text
            lines += Json.parseToJsonElement(first.readText()).jsonObject.text("line")
            Files.createFile(marker)
            for (frame in incoming) {
              if (frame is Frame.Text) {
                lines += Json.parseToJsonElement(frame.readText()).jsonObject.text("line")
              }
            }
          }
        }

        assertEquals(listOf("first", "second"), lines)
        awaitState(id, "SUCCEEDED")
      }

  @Test
  fun `a log stream starts after the given sequence number`() = testApplication {
    engine()
    val hash =
        upload(
            "three",
            """System.out.println("a"); System.out.println("b"); System.out.println("c");""",
        )
    val id = start(hash, "three")
    awaitState(id, "SUCCEEDED")
    val wsClient = createClient { install(WebSockets) }

    val lines = mutableListOf<String>()
    wsClient.webSocket("/api/v1/runs/$id/log/stream?after=1", request = bearer(TestTokens.ALICE)) {
      withTimeout(30_000) {
        for (frame in incoming) {
          if (frame is Frame.Text) {
            lines += Json.parseToJsonElement(frame.readText()).jsonObject.text("line")
          }
        }
      }
    }

    assertEquals(listOf("b", "c"), lines)
  }

  @Test
  fun `a log stream needs a token and a visible run`() = testApplication {
    engine()
    val hash = upload("quiet")
    val id = start(hash, "quiet")
    awaitState(id, "SUCCEEDED")
    val wsClient = createClient { install(WebSockets) }

    val unauthenticated = runCatching { wsClient.webSocket("/api/v1/runs/$id/log/stream") {} }
    val hidden = runCatching {
      wsClient.webSocket("/api/v1/runs/$id/log/stream", request = bearer(TestTokens.BOB)) {}
    }

    assertTrue(unauthenticated.isFailure, "the handshake is refused without a token")
    assertTrue(hidden.isFailure, "the handshake is refused for a run the caller cannot see")
  }

  // ---- directories and telemetry are wired into the Engine ----

  @Test
  fun `the Engine prepares and removes run directories and reports it`() = testApplication {
    val logs = SnapshotListAppender().also { it.start() }
    val logger = LoggerFactory.getLogger("dev.lawlan.runline.engine.WorkspaceTelemetry") as Logger
    logger.addAppender(logs)
    try {
      val shared = Files.createTempDirectory(dir, "shared")
      val runs = Files.createTempDirectory(dir, "runs")
      engine("workspace.sharedRoot" to shared.toString(), "workspace.runRoot" to runs.toString())
      val hash =
          upload(
              "tidy",
              """context.getFiles().writeText(FileScope.PIPELINE_SHARED, "kept", "x");""",
          )

      val id = start(hash, "tidy")
      awaitState(id, "SUCCEEDED")

      assertEquals("x", Files.readString(shared.resolve("tidy").resolve("kept")))
      assertFalse(
          Files.exists(runs.resolve("tidy").resolve(id)),
          "a successful run's directory is removed",
      )
      val messages = logs.snapshot().map { it.formattedMessage }
      assertTrue(
          messages.any { it.contains("Created RUN_PRIVATE") && it.contains(id) },
          "$messages",
      )
      assertTrue(
          messages.any { it.contains("Removed RUN_PRIVATE") && it.contains(id) },
          "$messages",
      )
    } finally {
      logger.detachAppender(logs)
    }
  }

  // ---- restart and shutdown ----

  @Test
  fun `a run left unfinished by a previous process is marked interrupted when the Engine starts`() {
    val dataSource = dataSourceOf(database)
    val hash = StoredPipelines(PostgresArtifactStore(dataSource), dir).save("v1", "orphan")
    val definition = PostgresDefinitionStore(dataSource).find(hash, "orphan")!!
    val store = PostgresRunStore(dataSource)
    val id = UUID.randomUUID()
    store.insert(
        NewRun(id, definition.id, RunSource.Manual("alice"), emptyMap(), Instant.now(), null)
    )
    store.advance(id, RunState.RUNNING, Instant.now())

    testApplication {
      engine()

      val run = get("/api/v1/runs/$id", TestTokens.ROOT).json()
      assertEquals("INTERRUPTED", run.text("state"))
      assertNotNull(run["finishedAt"])
    }
  }

  @Test
  fun `a shutdown stops running runs and leaves them interrupted`() {
    var id = ""
    testApplication {
      engine()
      val hash = upload("waits", RunHarness.WAIT_FOR_STOP)
      id = start(hash, "waits")
      awaitState(id, "RUNNING")
    }

    val store = PostgresRunStore(dataSourceOf(database))
    val run = store.find(UUID.fromString(id), Visibility.All)!!
    assertEquals(RunState.INTERRUPTED, run.state)
  }
}
