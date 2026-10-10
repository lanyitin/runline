package dev.lawlan.runline.engine.packaged

import dev.lawlan.runline.accessors.fake.FreezableForward
import dev.lawlan.runline.accessors.jdbc.RealPostgres
import dev.lawlan.runline.accessors.support.TestDirectories
import dev.lawlan.runline.engine.config.DatabaseConfig
import dev.lawlan.runline.engine.run.RunJarFiles
import dev.lawlan.runline.engine.support.Keystores
import dev.lawlan.runline.engine.support.ManagedProcess
import dev.lawlan.runline.engine.support.PipelineJars
import dev.lawlan.runline.engine.support.PostgresTestContainer
import dev.lawlan.runline.engine.support.StoppablePostgres
import dev.lawlan.runline.engine.support.StuckServer
import dev.lawlan.runline.engine.support.TestTimeouts
import dev.lawlan.runline.engine.support.TimedHttp
import dev.lawlan.runline.engine.support.awaitCondition
import dev.lawlan.runline.engine.support.awaitWithin
import dev.lawlan.runline.engine.support.getWithin
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.serialization.json.*

/**
 * Starts the Engine the way it is deployed: `engine.jar` as its own process with the run runtime in
 * a directory of its own, a real PostgreSQL, and everything given through environment variables.
 * Nothing here runs inside the test JVM's class path, so what a run can see is what a deployed
 * Engine gives it.
 */
class PackagedEngineTest {
  private val dist = Path.of(System.getProperty("runline.dist"))
  private val javaBin = System.getProperty("runline.java")
  private val work: Path = TestDirectories.forThisTest("packaged-engine")
  private val http = TimedHttp()
  // Asking whether the Engine is up: an Engine that is still starting may take a connection and
  // not answer it, so each try is short and the start as a whole has the limit.
  private val probe = TimedHttp(requestTimeout = TestTimeouts.readinessProbe)
  private val processes = mutableListOf<ManagedProcess>()
  private val port = ServerSocket(0).use { it.localPort }
  private val base = "http://localhost:$port"

  /** Every process started here ends with the test, whether it passed, failed or timed out. */
  @AfterTest
  fun stop() {
    val failures = processes.mapNotNull { runCatching { it.close() }.exceptionOrNull() }
    failures.firstOrNull()?.let { throw it }
    val left = processes.filter { it.process.isAlive }
    check(left.isEmpty()) {
      "left running: ${left.map { "${it.description} (pid ${it.process.pid()})" }}"
    }
  }

  private fun environment(
      database: DatabaseConfig,
      runtimeDir: Path,
      extra: Map<String, String> = emptyMap(),
  ) =
      mapOf(
          "PORT" to port.toString(),
          "POSTGRES_URL" to database.url,
          "POSTGRES_USER" to database.user,
          "POSTGRES_PASSWORD" to database.password,
          "API_TOKENS" to "alice:developer:tok-alice-0123456789,root:admin:tok-root-0123456789",
          "RUNLINE_SHARED_ROOT" to work.resolve("shared").toString(),
          "RUNLINE_RUN_ROOT" to work.resolve("runs").toString(),
          "RUNLINE_RESOURCE_ROOT" to work.resolve("resources").toString(),
          "RUNLINE_WORKSPACE_MAX_BYTES" to "1000000",
          "RUNLINE_FAILED_RUN_RETENTION_SECONDS" to "3600",
          "RUNLINE_MAX_CONCURRENT_RUNS" to "2",
          "RUNLINE_RUNTIME_DIR" to runtimeDir.toString(),
          "RUNLINE_SHUTDOWN_GRACE_SECONDS" to "5",
          "OTEL_TRACES_EXPORTER" to "none",
          "OTEL_LOGS_EXPORTER" to "none",
          "OTEL_METRICS_EXPORTER" to "none",
      ) + extra

  private fun launch(
      description: String,
      env: Map<String, String>,
      vararg args: String,
      log: String,
  ): ManagedProcess =
      ManagedProcess.start(
              description,
              listOf(javaBin) + args,
              work.resolve(log),
              environment = env,
              directory = work,
          )
          .also { processes += it }

  private fun output(log: String) = Files.readString(work.resolve(log))

  /** The end of a process's output, for the message of a failure that is about waiting. */
  private fun tail(log: String, lines: Int = 40) =
      output(log).lines().dropLastWhile { it.isEmpty() }.takeLast(lines).joinToString("\n")

  /** The one-off migration process, from the packaged jar. */
  private fun migrate(database: DatabaseConfig, runtimeDir: Path) {
    val process =
        launch(
            "the migration process",
            environment(database, runtimeDir),
            "-cp",
            dist.resolve("engine.jar").toString(),
            "dev.lawlan.runline.engine.db.MigrateKt",
            log = "migrate.log",
        )
    assertEquals(0, process.awaitExit(TestTimeouts.migration), output("migrate.log"))
  }

  private fun startEngine(
      database: DatabaseConfig,
      runtimeDir: Path,
      extra: Map<String, String> = emptyMap(),
      /** Options of the JVM, before the jar, such as where its temporary files go. */
      jvmOptions: List<String> = emptyList(),
  ): ManagedProcess {
    val process =
        launch(
            "the packaged Engine",
            environment(database, runtimeDir, extra),
            *jvmOptions.toTypedArray(),
            "-jar",
            dist.resolve("engine.jar").toString(),
            log = "engine.log",
        )
    val deadline = System.nanoTime() + TestTimeouts.engineStart.toNanos()
    while (System.nanoTime() < deadline) {
      check(process.process.isAlive) { "the Engine exited early:\n${output("engine.log")}" }
      if (runCatching { probe.get("$base/openapi").statusCode() }.getOrNull() == 200) return process
      Thread.sleep(200)
    }
    fail(
        "the Engine did not come up within ${TestTimeouts.engineStart.toMillis()} ms:\n" +
            process.outputTail()
    )
  }

  private fun get(path: String, token: String?): HttpResponse<String> =
      http.get("$base$path", token)

  private fun send(
      method: String,
      path: String,
      token: String,
      body: ByteArray,
      contentType: String,
  ): HttpResponse<String> =
      http.send(
          HttpRequest.newBuilder(URI("$base$path"))
              .header("Authorization", "Bearer $token")
              .header("Content-Type", contentType)
              .method(method, HttpRequest.BodyPublishers.ofByteArray(body))
      )

  private fun json(response: HttpResponse<String>) =
      Json.parseToJsonElement(response.body()).jsonObject

  /** Uploads a pipeline written in Java, allows it to run unsafe, and starts a run of it. */
  private fun uploadAndRun(name: String, body: String): String {
    val jar =
        PipelineJars.build(
            work,
            "$name.jar",
            mapOf(
                "demo.Probe" to
                    PipelineJars.pipeline(
                        "demo.Probe",
                        name,
                        "network = @AccessLimit(allow = {}), processes = @AccessLimit(allow = {})",
                        "",
                        body,
                    )
            ),
        )
    val upload =
        send(
            "POST",
            "/api/v1/artifacts",
            ALICE,
            Files.readAllBytes(jar),
            "application/octet-stream",
        )
    assertEquals(201, upload.statusCode(), upload.body())
    val hash = json(upload)["contentHash"]!!.jsonPrimitive.content
    // Whatever the verdict, the administrator allows it to run (a safe one needs no permission).
    val allow =
        send(
            "PUT",
            "/api/v1/definitions/$hash/$name/unsafe-execution",
            ROOT,
            """{"allow":true}""".toByteArray(),
            "application/json",
        )
    assertEquals(200, allow.statusCode(), allow.body())
    val run =
        send(
            "POST",
            "/api/v1/runs",
            ALICE,
            """{"contentHash":"$hash","pipeline":"$name"}""".toByteArray(),
            "application/json",
        )
    assertEquals(201, run.statusCode(), run.body())
    return json(run)["runId"]!!.jsonPrimitive.content
  }

  private fun awaitState(runId: String, vararg states: String): JsonObject {
    val deadline = System.nanoTime() + TestTimeouts.condition.toNanos()
    var last = "no answer yet"
    while (System.nanoTime() < deadline) {
      val run = json(get("/api/v1/runs/$runId", ROOT))
      if (run["state"]!!.jsonPrimitive.content in states) return run
      last = run["state"]!!.jsonPrimitive.content
      Thread.sleep(100)
    }
    fail(
        "run $runId did not reach $states within ${TestTimeouts.condition.toMillis()} ms " +
            "(last state $last):\n${tail("engine.log")}"
    )
  }

  private fun logLines(runId: String): List<String> =
      json(get("/api/v1/runs/$runId/log", ROOT))["entries"]!!.jsonArray.map {
        it.jsonObject["line"]!!.jsonPrimitive.content
      }

  @Test
  fun `a run in the packaged Engine sees the Runner and core but nothing of the Engine`() {
    val database = PostgresTestContainer.newDatabase()
    val runtime = dist.resolve("run-runtime")
    migrate(database, runtime)
    startEngine(database, runtime)

    val runId =
        uploadAndRun(
            "probe",
            """
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            String[] names = {
              "dev.lawlan.runline.engine.DIKt",
              "dev.lawlan.runline.engine.run.RunService",
              "dev.lawlan.runline.analyzer.SafetyAnalyzer",
              "io.ktor.server.application.Application",
              "org.postgresql.Driver",
              "org.flywaydb.core.Flyway",
              "ch.qos.logback.classic.Logger",
              "io.opentelemetry.api.OpenTelemetry",
              "dev.lawlan.runline.core.Pipeline",
              "dev.lawlan.runline.runner.isolated.RunEntry",
              "kotlin.Unit"
            };
            for (String name : names) {
              try {
                Class<?> type = Class.forName(name, false, loader);
                System.out.println("visible " + name + " from " + (type.getClassLoader() == loader ? "the run's own loader" : "another loader"));
              } catch (ClassNotFoundException e) {
                System.out.println("hidden " + name);
              }
            }
            """
                .trimIndent(),
        )

    val run = awaitState(runId, "SUCCEEDED", "FAILED")
    assertEquals("SUCCEEDED", run["state"]!!.jsonPrimitive.content, output("engine.log"))
    val lines = logLines(runId)
    for (engineClass in
        listOf(
            "dev.lawlan.runline.engine.DIKt",
            "dev.lawlan.runline.engine.run.RunService",
            "dev.lawlan.runline.analyzer.SafetyAnalyzer",
            "io.ktor.server.application.Application",
            "org.postgresql.Driver",
            "org.flywaydb.core.Flyway",
            "ch.qos.logback.classic.Logger",
            "io.opentelemetry.api.OpenTelemetry",
        )) {
      assertTrue("hidden $engineClass" in lines, "$engineClass should be hidden from a run: $lines")
    }
    for (own in
        listOf(
            "dev.lawlan.runline.core.Pipeline",
            "dev.lawlan.runline.runner.isolated.RunEntry",
            "kotlin.Unit",
        )) {
      assertTrue(
          "visible $own from the run's own loader" in lines,
          "$own should come from the run's own class loader: $lines",
      )
    }
  }

  @Test
  fun `the packaged Engine reports the commit it was built from, to anyone and with a token`() {
    val database = PostgresTestContainer.newDatabase()
    val runtime = dist.resolve("run-runtime")
    migrate(database, runtime)
    startEngine(database, runtime)
    val head =
        ProcessBuilder("git", "rev-parse", "HEAD")
            .directory(Path.of(System.getProperty("runline.repoRoot")).toFile())
            .start()
            .let { it.inputStream.bufferedReader().readText().trim() }

    val info = get("/api/v1/info", token = null)
    val system = get("/api/v1/system", ALICE)

    assertEquals(200, info.statusCode(), info.body())
    assertEquals(head, json(info)["commitHash"]!!.jsonPrimitive.content)
    assertEquals(200, system.statusCode(), system.body())
    assertEquals(head, json(system)["commitHash"]!!.jsonPrimitive.content)
    assertEquals("alice", json(system)["caller"]!!.jsonObject["name"]!!.jsonPrimitive.content)
  }

  @Test
  fun `the packaged Engine refuses a run runtime directory that holds the Engine`() {
    val database = PostgresTestContainer.newDatabase()
    migrate(database, dist.resolve("run-runtime"))
    val mixed = Files.createDirectory(work.resolve("mixed"))
    Files.copy(dist.resolve("engine.jar"), mixed.resolve("engine.jar"))
    dist.resolve("run-runtime").toFile().listFiles()!!.forEach {
      Files.copy(it.toPath(), mixed.resolve(it.name))
    }

    val process =
        launch(
            "the Engine, which should refuse to start",
            environment(database, mixed),
            "-jar",
            dist.resolve("engine.jar").toString(),
            log = "engine.log",
        )

    assertNotEquals(0, process.awaitExit(TestTimeouts.processExit))
    assertTrue(output("engine.log").contains("Engine classes"), output("engine.log"))
  }

  @Test
  fun `the packaged Engine takes its retention settings from the environment and names each bad one`() {
    val database = PostgresTestContainer.newDatabase()
    migrate(database, dist.resolve("run-runtime"))
    val bad =
        mapOf(
            "RUNLINE_RUN_RETENTION_SECONDS" to "soon",
            "RUNLINE_RUN_LOG_RETENTION_SECONDS" to "soon",
            // Below the floor of the dedup window.
            "RUNLINE_WEBHOOK_DEDUP_WINDOW_SECONDS" to "60",
            "RUNLINE_CRON_FIRING_RETENTION_SECONDS" to "soon",
            "RUNLINE_RETENTION_INTERVAL_SECONDS" to "soon",
            "RUNLINE_RETENTION_BATCH_SIZE" to "soon",
        )

    val process =
        launch(
            "the Engine, which should refuse to start",
            environment(database, dist.resolve("run-runtime"), bad),
            "-jar",
            dist.resolve("engine.jar").toString(),
            log = "engine.log",
        )

    assertNotEquals(0, process.awaitExit(TestTimeouts.processExit))
    for (key in
        listOf(
            "retention.runSeconds",
            "retention.logSeconds",
            "retention.webhookDedupWindowSeconds",
            "retention.cronFiringSeconds",
            "retention.intervalSeconds",
            "retention.batchSize",
        )) {
      assertTrue(
          output("engine.log").contains(key),
          "$key is not named in:\n${output("engine.log")}",
      )
    }
  }

  @Test
  fun `stopping the packaged Engine stops its runs and leaves them interrupted`() {
    val database = PostgresTestContainer.newDatabase()
    val runtime = dist.resolve("run-runtime")
    migrate(database, runtime)
    val engine = startEngine(database, runtime)
    val runId =
        uploadAndRun(
            "sleeper",
            "try { while (true) Thread.sleep(10); } catch (InterruptedException e) { throw new RuntimeException(e); }",
        )
    awaitState(runId, "RUNNING")

    engine.terminate() // SIGTERM, as a platform stops a container
    engine.awaitExit(TestTimeouts.processExit)

    val state =
        PostgresTestContainer.connect(database).use { c ->
          c.prepareStatement("SELECT state FROM run WHERE id = ?::uuid").use { s ->
            s.setString(1, runId)
            s.executeQuery().use { rs ->
              rs.next()
              rs.getString(1)
            }
          }
        }
    assertEquals("INTERRUPTED", state, output("engine.log"))
  }

  private fun uploadAllowed(fqcn: String, name: String, definition: String, body: String): String {
    val jar =
        PipelineJars.build(
            work,
            "$name-${System.nanoTime()}.jar",
            mapOf(fqcn to PipelineJars.pipeline(fqcn, name, definition, "", body)),
        )
    val upload =
        send(
            "POST",
            "/api/v1/artifacts",
            ALICE,
            Files.readAllBytes(jar),
            "application/octet-stream",
        )
    assertEquals(201, upload.statusCode(), upload.body())
    val hash = json(upload)["contentHash"]!!.jsonPrimitive.content
    val allow =
        send(
            "PUT",
            "/api/v1/definitions/$hash/$name/unsafe-execution",
            ROOT,
            """{"allow":true}""".toByteArray(),
            "application/json",
        )
    assertEquals(200, allow.statusCode(), allow.body())
    return hash
  }

  private fun startRun(hash: String, name: String): String {
    val run =
        send(
            "POST",
            "/api/v1/runs",
            ALICE,
            """{"contentHash":"$hash","pipeline":"$name"}""".toByteArray(),
            "application/json",
        )
    assertEquals(201, run.statusCode(), run.body())
    return json(run)["runId"]!!.jsonPrimitive.content
  }

  @Test
  fun `in the packaged Engine two runs in different class loaders do not hold the same resource together`() {
    val database = PostgresTestContainer.newDatabase()
    val runtime = dist.resolve("run-runtime")
    migrate(database, runtime)
    startEngine(database, runtime)
    val define =
        send(
            "POST",
            "/api/v1/resources",
            ROOT,
            """{"name":"lemonade","capacity":1}""".toByteArray(),
            "application/json",
        )
    assertEquals(201, define.statusCode(), define.body())
    val definition =
        "files = {@FileAccess(scope = FileScope.PIPELINE_SHARED, mode = FileMode.READ_WRITE)}, " +
            "network = @AccessLimit(allow = {}), processes = @AccessLimit(allow = {}), " +
            "resources = {\"lemonade\"}"
    val loader = "String.valueOf(System.identityHashCode(getClass().getClassLoader()))"
    val first =
        uploadAllowed(
            "demo.First",
            "excl",
            definition,
            """
            context.getFiles().writeText(FileScope.PIPELINE_SHARED, "a-loader", $loader);
            context.getFiles().writeText(FileScope.PIPELINE_SHARED, "a-started", "x");
            try {
              while (!context.getFiles().exists(FileScope.PIPELINE_SHARED, "release")) Thread.sleep(10);
            } catch (InterruptedException e) { throw new RuntimeException(e); }
            context.getFiles().writeText(FileScope.PIPELINE_SHARED, "a-ended", "x");
            """
                .trimIndent(),
        )
    val second =
        uploadAllowed(
            "demo.Second",
            "excl",
            definition,
            """
            context.getFiles().writeText(FileScope.PIPELINE_SHARED, "b-saw-a-ended",
                String.valueOf(context.getFiles().exists(FileScope.PIPELINE_SHARED, "a-ended")));
            context.getFiles().writeText(FileScope.PIPELINE_SHARED, "b-loader", $loader);
            """
                .trimIndent(),
        )
    val sharedDir = work.resolve("shared").resolve("excl")
    val a = startRun(first, "excl")
    awaitState(a, "RUNNING")
    awaitCondition(
        "the first run to write a-started",
        diagnostics = { tail("engine.log") },
    ) {
      Files.exists(sharedDir.resolve("a-started"))
    }

    val b = startRun(second, "excl")

    awaitState(b, "WAITING_FOR_RESOURCES")
    val view = json(get("/api/v1/resources/lemonade", ROOT))
    assertEquals(
        listOf(a),
        view["holders"]!!.jsonArray.map { it.jsonObject["runId"]!!.jsonPrimitive.content },
    )
    assertEquals(
        listOf(b),
        view["waiters"]!!.jsonArray.map { it.jsonObject["runId"]!!.jsonPrimitive.content },
    )
    assertFalse(Files.exists(sharedDir.resolve("b-loader")), "the second run must not have started")
    assertEquals(403, get("/api/v1/resources/lemonade", ALICE).statusCode())
    Files.writeString(sharedDir.resolve("release"), "x")
    assertEquals("SUCCEEDED", awaitState(a, "SUCCEEDED", "FAILED")["state"]!!.jsonPrimitive.content)
    assertEquals("SUCCEEDED", awaitState(b, "SUCCEEDED", "FAILED")["state"]!!.jsonPrimitive.content)
    assertEquals("true", Files.readString(sharedDir.resolve("b-saw-a-ended")))
    assertNotEquals(
        Files.readString(sharedDir.resolve("a-loader")),
        Files.readString(sharedDir.resolve("b-loader")),
    )
  }

  // ---- graceful shutdown (WI-18): what WI-08's test of interrupted runs does not cover ----

  /** A jar of a real compiled pipeline, as the bytes an upload sends. */
  private fun jarBytes(name: String): ByteArray =
      Files.readAllBytes(
          PipelineJars.build(
              work,
              "$name-${System.nanoTime()}.jar",
              mapOf("demo.Slow" to PipelineJars.pipeline("demo.Slow", name)),
          )
      )

  /** A request body that takes [totalMillis] to arrive, in [chunks] pieces. */
  private fun slowBody(
      bytes: ByteArray,
      chunks: Int,
      totalMillis: Long,
      onFirstChunkSent: () -> Unit,
  ): HttpRequest.BodyPublisher {
    val size = (bytes.size + chunks - 1) / chunks
    val stream =
        object : java.io.InputStream() {
          private var offset = 0
          private var pending: ByteArray = ByteArray(0)
          private var position = 0

          override fun read(): Int = throw UnsupportedOperationException()

          override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            if (position >= pending.size) {
              if (offset >= bytes.size) return -1
              if (offset > 0) {
                // The client came back for more: the request headers and the first chunk are out.
                onFirstChunkSent()
                Thread.sleep(totalMillis / chunks)
              }
              pending = bytes.copyOfRange(offset, minOf(offset + size, bytes.size))
              offset += pending.size
              position = 0
            }
            val n = minOf(len, pending.size - position)
            System.arraycopy(pending, position, buffer, off, n)
            position += n
            return n
          }
        }
    return HttpRequest.BodyPublishers.ofInputStream { stream }
  }

  private fun uploadSlowly(
      bytes: ByteArray,
      totalMillis: Long,
      chunks: Int = 10,
      onFirstChunkSent: () -> Unit,
  ) =
      http.client.sendAsync(
          HttpRequest.newBuilder(URI("$base/api/v1/artifacts"))
              .header("Authorization", "Bearer $ALICE")
              .header("Content-Type", "application/octet-stream")
              .timeout(Duration.ofMillis(totalMillis) + TestTimeouts.httpRequest)
              .POST(slowBody(bytes, chunks, totalMillis, onFirstChunkSent))
              .build(),
          HttpResponse.BodyHandlers.ofString(),
      )

  /** Connections to [database] other than the one asking, as PostgreSQL sees them. */
  private fun otherConnections(database: DatabaseConfig): Int =
      PostgresTestContainer.connect(database).use { c ->
        c.createStatement().use { s ->
          s.executeQuery(
                  "SELECT count(*) FROM pg_stat_activity " +
                      "WHERE datname = current_database() AND pid <> pg_backend_pid()"
              )
              .use {
                it.next()
                it.getInt(1)
              }
        }
      }

  /** What a new request gets while the Engine is stopping: "refused" or the 503 it answers. */
  private fun newRequestOutcome(): String =
      try {
        val response = get("/api/v1/definitions", ALICE)
        if (response.statusCode() == 503 && response.body().contains("shutting_down")) "refused"
        else "served: ${response.statusCode()} ${response.body()}"
      } catch (e: java.io.IOException) {
        "refused"
      }

  /** [newRequestOutcome] until it is "refused" or ten seconds pass; returns the last outcome. */
  private fun awaitRefusal(): String {
    val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
    var outcome = newRequestOutcome()
    while (outcome != "refused" && System.nanoTime() < deadline) {
      Thread.sleep(20)
      outcome = newRequestOutcome()
    }
    return outcome
  }

  @Test
  fun `on SIGTERM a request in flight completes, new requests are refused and the Engine exits within the grace time`() {
    val database = PostgresTestContainer.newDatabase()
    val runtime = dist.resolve("run-runtime")
    migrate(database, runtime)
    val engine = startEngine(database, runtime, mapOf("RUNLINE_SHUTDOWN_GRACE_SECONDS" to "10"))
    val received = java.util.concurrent.CountDownLatch(1)
    val upload = uploadSlowly(jarBytes("in-flight"), totalMillis = 4_000) { received.countDown() }
    // The request is being received: its body takes four seconds to arrive. The Engine shows no
    // sign
    // of having taken the request up, so once the client has begun sending it gets a second to do
    // so.
    received.awaitWithin("the upload to begin arriving at the Engine")
    Thread.sleep(1_000)

    val signalled = System.nanoTime()
    engine.terminate() // SIGTERM, as a platform stops a container

    // No new requests: they are turned away (503) or cannot connect, never served.
    // The Engine learns of the signal a moment after it is sent: ask until it turns requests away.
    assertEquals("refused", awaitRefusal(), "a stopping Engine must not serve new requests")
    assertFalse(upload.isDone, "the upload should still be in flight while the Engine stops")

    // The request in flight is answered, in full.
    val response = upload.getWithin("the answer to the upload that was in flight")
    assertEquals(201, response.statusCode(), response.body())
    assertTrue(json(response)["contentHash"] != null)

    // And the process ends within the grace time, with nothing of it left in the database.
    engine.awaitExit(Duration.ofSeconds(10)) // the grace time
    val elapsed = Duration.ofNanos(System.nanoTime() - signalled)
    assertTrue(
        elapsed <= Duration.ofSeconds(10),
        "stopping took $elapsed:\n${output("engine.log")}",
    )
    assertEquals(0, otherConnections(database), "the Engine left database connections open")
  }

  @Test
  fun `a request that outlasts the grace time does not keep the Engine from exiting within it`() {
    val database = PostgresTestContainer.newDatabase()
    val runtime = dist.resolve("run-runtime")
    migrate(database, runtime)
    val engine = startEngine(database, runtime, mapOf("RUNLINE_SHUTDOWN_GRACE_SECONDS" to "3"))
    val received = java.util.concurrent.CountDownLatch(1)
    val upload = uploadSlowly(jarBytes("too-slow"), totalMillis = 60_000) { received.countDown() }
    received.awaitWithin("the upload to begin arriving at the Engine")
    Thread.sleep(1_000) // as above: the Engine shows no sign of having taken the request up

    val signalled = System.nanoTime()
    engine.terminate()

    // The grace time is three seconds; the rest of the allowance is for the JVM to tear down.
    engine.awaitExit(Duration.ofSeconds(8))
    val elapsed = Duration.ofNanos(System.nanoTime() - signalled)
    assertTrue(elapsed <= Duration.ofSeconds(8), "stopping took $elapsed")
    assertEquals(0, otherConnections(database))
    assertTrue(
        runCatching { upload.get(5, TimeUnit.SECONDS).statusCode() }.getOrNull() != 201,
        "a request cut off by the shutdown is not a success",
    )
  }

  @Test
  fun `an open log stream does not make the Engine wait out the grace time`() {
    val database = PostgresTestContainer.newDatabase()
    val runtime = dist.resolve("run-runtime")
    migrate(database, runtime)
    val engine = startEngine(database, runtime, mapOf("RUNLINE_SHUTDOWN_GRACE_SECONDS" to "20"))
    val runId =
        uploadAndRun(
            "streamed",
            """
            System.out.println("started");
            try { while (true) Thread.sleep(10); } catch (InterruptedException e) { throw new RuntimeException(e); }
            """
                .trimIndent(),
        )
    awaitState(runId, "RUNNING")
    val opened = java.util.concurrent.CountDownLatch(1)
    http.client
        .newWebSocketBuilder()
        .header("Authorization", "Bearer $ALICE")
        .buildAsync(
            URI("ws://localhost:$port/api/v1/runs/$runId/log/stream"),
            object : java.net.http.WebSocket.Listener {
              override fun onOpen(webSocket: java.net.http.WebSocket) {
                opened.countDown()
                webSocket.request(1)
              }
            },
        )
    opened.awaitWithin("the log stream to open", Duration.ofSeconds(10))

    val signalled = System.nanoTime()
    engine.terminate()

    engine.awaitExit(Duration.ofSeconds(15))
    val elapsed = Duration.ofNanos(System.nanoTime() - signalled)
    assertTrue(elapsed < Duration.ofSeconds(15), "stopping took $elapsed with a log stream open")
  }

  // ---- the grace time as one budget for the whole shutdown (WI-64) ----

  @Test
  fun `a request that never ends, a run whose release hangs and a collector that never answers stop the Engine within the grace time and the wrap-up time`() {
    val grace = Duration.ofSeconds(5)
    val database = PostgresTestContainer.newDatabase()
    val runtime = dist.resolve("run-runtime")
    migrate(database, runtime)
    // The database of a `jdbc-pool` resource, behind a network that can stop answering (WI-62).
    val target = RealPostgres.newDatabase()
    val role = "shut_" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
    val password = "pw-" + java.util.UUID.randomUUID().toString().replace("-", "")
    target.createRole(role, password, "GRANT ALL ON SCHEMA public TO $role")
    val forward = FreezableForward(RealPostgres.host, RealPostgres.port)
    // A collector that takes every export and never answers (WI-63).
    val collector = StuckServer("")
    val keystores = Keystores(Files.createTempDirectory(work, "keystore"))
    val keystore = keystores.pkcs12("engine.p12", mapOf("db-pw" to password), "storepass-64")
    val settings =
        mapOf(
            "RUNLINE_SHUTDOWN_GRACE_SECONDS" to "${grace.seconds}",
            "RUNLINE_KEYSTORE_PATH" to keystore.toString(),
            "RUNLINE_KEYSTORE_PASSWORD_FILE" to
                keystores.passwordFile("storepass-64", "engine.pw").toString(),
            "OTEL_TRACES_EXPORTER" to "otlp",
            "OTEL_METRICS_EXPORTER" to "otlp",
            "OTEL_LOGS_EXPORTER" to "otlp",
            "OTEL_EXPORTER_OTLP_PROTOCOL" to "http/protobuf",
            "OTEL_EXPORTER_OTLP_ENDPOINT" to collector.base,
        )
    try {
      val engine = startEngine(database, runtime, settings)
      val define =
          send(
              "POST",
              "/api/v1/resources",
              ROOT,
              ("""{"name":"db","capacity":1,"type":"jdbc-pool","secretAlias":"db-pw",""" +
                      """"settings":{"kind":"postgresql","host":"${forward.host}",""" +
                      """"port":${forward.port},"database":"${target.name}",""" +
                      """"username":"$role"}}""")
                  .toByteArray(),
              "application/json",
          )
      assertEquals(201, define.statusCode(), define.body())
      val declaration =
          "files = {@FileAccess(scope = FileScope.PIPELINE_SHARED, mode = FileMode.READ_WRITE)}, " +
              "network = @AccessLimit(allow = {}), processes = @AccessLimit(allow = {}), " +
              "typedResources = {@TypedResource(name = \"db\", type = \"jdbc-pool\")}"
      // A run that holds a connection in an open transaction and does not stop when asked to.
      val deaf =
          uploadAllowed(
              "demo.Deaf",
              "deaf",
              declaration,
              """
              JdbcAccessor db = context.getAccessors().jdbcPool("db");
              db.begin();
              db.query("SELECT 1");
              context.getFiles().writeText(FileScope.PIPELINE_SHARED, "in-transaction", "x");
              while (true) {
                try { Thread.sleep(10); } catch (InterruptedException e) { }
              }
              """
                  .trimIndent(),
          )
      val runId = startRun(deaf, "deaf")
      awaitState(runId, "RUNNING")
      awaitCondition("the run to hold its transaction", diagnostics = { tail("engine.log") }) {
        Files.exists(work.resolve("shared").resolve("deaf").resolve("in-transaction"))
      }
      // Giving the connection back cannot get an answer now.
      forward.freeze()
      // A request that does not end within the grace time.
      val received = java.util.concurrent.CountDownLatch(1)
      // Its body arrives a little at a time, so that it is under way when the Engine is told to
      // stop.
      uploadSlowly(jarBytes("never-done"), totalMillis = 120_000, chunks = 200) {
        received.countDown()
      }
      received.awaitWithin("the upload to begin arriving at the Engine")
      Thread.sleep(1_000) // the Engine shows no sign of having taken the request up

      val signalled = System.nanoTime()
      engine.terminate()
      engine.awaitExit(grace + SHUTDOWN_WRAP_UP + Duration.ofSeconds(30))
      val elapsed = Duration.ofNanos(System.nanoTime() - signalled)

      // The worst case did happen: the request was still in flight when the grace time ran out.
      assertTrue(output("engine.log").contains("still in flight"), tail("engine.log", 80))
      assertTrue(
          elapsed <= grace + SHUTDOWN_WRAP_UP,
          "stopping took $elapsed, more than the grace time $grace and the wrap-up time " +
              "$SHUTDOWN_WRAP_UP:\n${tail("engine.log", 80)}",
      )
      assertEquals(
          "INTERRUPTED",
          PostgresTestContainer.connect(database).use { c ->
            c.prepareStatement("SELECT state FROM run WHERE id = ?::uuid").use { s ->
              s.setString(1, runId)
              s.executeQuery().use { rs ->
                rs.next()
                rs.getString(1)
              }
            }
          },
          tail("engine.log", 80),
      )

      // After a restart the run is interrupted, nothing holds `db` and another run can use it.
      forward.thaw()
      startEngine(
          database,
          runtime,
          settings - "OTEL_TRACES_EXPORTER" - "OTEL_METRICS_EXPORTER" - "OTEL_LOGS_EXPORTER",
      )
      assertEquals("INTERRUPTED", awaitState(runId, "INTERRUPTED")["state"]!!.jsonPrimitive.content)
      val view = json(get("/api/v1/resources/db", ROOT))
      assertEquals(JsonArray(emptyList()), view["holders"], view.toString())
      val user =
          uploadAllowed(
              "demo.User",
              "user",
              declaration,
              """context.getAccessors().jdbcPool("db").query("SELECT 1");""",
          )
      val used = startRun(user, "user")
      val outcome = awaitState(used, "SUCCEEDED", "FAILED")
      assertEquals("SUCCEEDED", outcome["state"]!!.jsonPrimitive.content, outcome.toString())
      // And the database keeps no transaction of the Engine that stopped.
      awaitCondition(
          "the transaction of the Engine that stopped to be gone",
          diagnostics = { tail("engine.log") },
      ) {
        target
            .scalar(
                "SELECT count(*) FROM pg_stat_activity " +
                    "WHERE usename = '$role' AND state = 'idle in transaction'"
            )
            ?.toInt() == 0
      }
    } finally {
      forward.close()
      collector.close()
      target.close()
    }
  }

  // ---- the jars of runs that a shutdown gave up on (WI-67) ----

  /** The JVM option that makes [directory] the place of the Engine's temporary files. */
  private fun temporaryFilesIn(directory: Path) = listOf("-Djava.io.tmpdir=$directory")

  private fun stateInDatabase(database: DatabaseConfig, runId: String): String =
      PostgresTestContainer.connect(database).use { c ->
        c.prepareStatement("SELECT state FROM run WHERE id = ?::uuid").use { s ->
          s.setString(1, runId)
          s.executeQuery().use { rs ->
            rs.next()
            rs.getString(1)
          }
        }
      }

  private fun names(directory: Path): Set<String> =
      Files.list(directory).use { files -> files.map { it.fileName.toString() }.toList().toSet() }

  @Test
  fun `a run that does not stop when the Engine is told to leaves no jar behind, within the grace time and the wrap-up time`() {
    val grace = Duration.ofSeconds(3)
    val database = PostgresTestContainer.newDatabase()
    val runtime = dist.resolve("run-runtime")
    migrate(database, runtime)
    val temporary = Files.createDirectory(work.resolve("tmp"))
    val engine =
        startEngine(
            database,
            runtime,
            mapOf("RUNLINE_SHUTDOWN_GRACE_SECONDS" to "${grace.seconds}"),
            temporaryFilesIn(temporary),
        )
    val runId =
        uploadAndRun(
            "deaf",
            "while (true) { try { Thread.sleep(10); } catch (InterruptedException e) { } }",
        )
    awaitState(runId, "RUNNING")
    assertEquals(1, names(temporary).count { it.startsWith("run-") }, "${names(temporary)}")

    val signalled = System.nanoTime()
    engine.terminate() // SIGTERM, as a platform stops a container
    engine.awaitExit(grace + SHUTDOWN_WRAP_UP + Duration.ofSeconds(30))
    val elapsed = Duration.ofNanos(System.nanoTime() - signalled)

    assertTrue(
        output("engine.log").contains("did not stop within the grace time"),
        tail("engine.log", 80),
    )
    assertEquals("INTERRUPTED", stateInDatabase(database, runId), tail("engine.log", 80))
    assertEquals(emptySet(), names(temporary).filter { it.startsWith("run-") }.toSet())
    assertTrue(
        elapsed <= grace + SHUTDOWN_WRAP_UP,
        "stopping took $elapsed, more than the grace time $grace and the wrap-up time " +
            "$SHUTDOWN_WRAP_UP:\n${tail("engine.log", 80)}",
    )
  }

  @Test
  fun `the jar an earlier Engine process left for a run is removed at the next start, and nothing else beside it`() {
    val database = PostgresTestContainer.newDatabase()
    val runtime = dist.resolve("run-runtime")
    migrate(database, runtime)
    val temporary = Files.createDirectory(work.resolve("tmp"))
    val first = startEngine(database, runtime, jvmOptions = temporaryFilesIn(temporary))
    val runId = uploadAndRun("quick", "")
    assertEquals(
        "SUCCEEDED",
        awaitState(runId, "SUCCEEDED", "FAILED")["state"]!!.jsonPrimitive.content,
    )
    first.terminate()
    first.awaitExit(TestTimeouts.processExit)
    // What the earlier process could not delete, as it named it, and what others keep there.
    val left = RunJarFiles.create(temporary, java.util.UUID.fromString(runId))
    Files.writeString(left, "the jar of an earlier process")
    val others =
        setOf(
            RunJarFiles.create(temporary, java.util.UUID.randomUUID()).fileName.toString(),
            Files.createFile(temporary.resolve("run-$runId.jar")).fileName.toString(),
            Files.createFile(temporary.resolve("run-6233326115724267127.jar")).fileName.toString(),
            Files.createFile(temporary.resolve("notes.txt")).fileName.toString(),
        )

    startEngine(database, runtime, jvmOptions = temporaryFilesIn(temporary))

    assertFalse(Files.exists(left), tail("engine.log"))
    assertTrue(names(temporary).containsAll(others), "${names(temporary)}")
  }

  @Test
  fun `the Engine starts when it cannot look for jars of an earlier process, and says so`() {
    val database = PostgresTestContainer.newDatabase()
    val runtime = dist.resolve("run-runtime")
    migrate(database, runtime)
    val missing = work.resolve("no-such-directory")

    startEngine(database, runtime, jvmOptions = temporaryFilesIn(missing))

    assertEquals(200, get("/api/v1/health/ready", token = null).statusCode())
    assertTrue(
        output("engine.log").lines().any { it.contains("WARN") && it.contains(missing.toString()) },
        tail("engine.log"),
    )
  }

  // ---- liveness and readiness probes (WI-29) ----

  /** The probes need no token; the answer's status and body. */
  private fun probe(name: String): Pair<Int, JsonObject> =
      get("/api/v1/health/$name", token = null).let { it.statusCode() to json(it) }

  @Test
  fun `on SIGTERM ready turns 503 shutting_down at once, live stays 200 and the request in flight completes`() {
    val database = PostgresTestContainer.newDatabase()
    val runtime = dist.resolve("run-runtime")
    migrate(database, runtime)
    val engine = startEngine(database, runtime, mapOf("RUNLINE_SHUTDOWN_GRACE_SECONDS" to "10"))
    assertEquals(
        200 to "ready",
        probe("ready").let { it.first to it.second["status"]!!.jsonPrimitive.content },
    )
    val received = java.util.concurrent.CountDownLatch(1)
    val upload = uploadSlowly(jarBytes("in-flight"), totalMillis = 4_000) { received.countDown() }
    received.awaitWithin("the upload to begin arriving at the Engine")
    Thread.sleep(1_000)

    val signalled = System.nanoTime()
    engine.terminate()

    // The Engine learns of the signal a moment after it is sent: ask until it says it is stopping.
    awaitCondition(
        "ready to turn 503 shutting_down after SIGTERM",
        diagnostics = { engine.outputTail() },
    ) {
      runCatching { probe("ready") }
          .getOrNull()
          ?.let { (status, body) ->
            status == 503 && body["status"]?.jsonPrimitive?.content == "shutting_down"
          } == true
    }
    // The grace time has not run out and the request in flight is still being received.
    assertFalse(upload.isDone, "the upload should still be in flight while ready says so")
    val (liveStatus, live) = probe("live")
    assertEquals(200, liveStatus)
    assertEquals("up", live["status"]!!.jsonPrimitive.content)
    val (_, ready) = probe("ready")
    assertEquals("failed", ready["checks"]!!.jsonObject["shutdown"]!!.jsonPrimitive.content)

    // Everything else is refused, the request in flight is answered in full, the process ends.
    assertEquals("refused", awaitRefusal())
    val response = upload.getWithin("the answer to the upload that was in flight")
    assertEquals(201, response.statusCode(), response.body())
    engine.awaitExit(Duration.ofSeconds(10))
    assertTrue(
        Duration.ofNanos(System.nanoTime() - signalled) <= Duration.ofSeconds(10),
        "stopping took longer than the grace time:\n${output("engine.log")}",
    )
  }

  // ---- deployment entry points (WI-38) ----

  @Test
  fun `the packaged Engine exits with code 0 after a graceful shutdown on SIGTERM`() {
    val database = PostgresTestContainer.newDatabase()
    val runtime = dist.resolve("run-runtime")
    migrate(database, runtime)
    val engine = startEngine(database, runtime)

    engine.terminate() // SIGTERM, as a platform stops a container

    assertEquals(0, engine.awaitExit(TestTimeouts.processExit), output("engine.log"))
  }

  @Test
  fun `the packaged Engine exits with a non-zero code when the database is not migrated`() {
    val database = PostgresTestContainer.newDatabase()
    val process =
        launch(
            "the Engine on a database nobody migrated",
            environment(database, dist.resolve("run-runtime")),
            "-jar",
            dist.resolve("engine.jar").toString(),
            log = "engine.log",
        )

    assertNotEquals(0, process.awaitExit(TestTimeouts.processExit), output("engine.log"))
    assertTrue(output("engine.log").contains("out of date"), output("engine.log"))
  }

  @Test
  fun `the packaged Engine exits with a non-zero code when its configuration is wrong`() {
    val database = PostgresTestContainer.newDatabase()
    migrate(database, dist.resolve("run-runtime"))
    val process =
        launch(
            "the Engine with a bad configuration",
            environment(
                database,
                dist.resolve("run-runtime"),
                mapOf("RUNLINE_MAX_CONCURRENT_RUNS" to "not-a-number"),
            ),
            "-jar",
            dist.resolve("engine.jar").toString(),
            log = "engine.log",
        )

    assertNotEquals(0, process.awaitExit(TestTimeouts.processExit), output("engine.log"))
    assertTrue(output("engine.log").contains("runs.maxConcurrent"), output("engine.log"))
  }

  @Test
  fun `the packaged Engine refuses two tokens with the same name, names it and prints no token`() {
    val database = PostgresTestContainer.newDatabase()
    migrate(database, dist.resolve("run-runtime"))
    val process =
        launch(
            "the Engine with two tokens named alike",
            environment(
                database,
                dist.resolve("run-runtime"),
                mapOf(
                    "API_TOKENS" to
                        "ann:developer:tok-first-0123456789,ann:admin:tok-second-0123456789"
                ),
            ),
            "-jar",
            dist.resolve("engine.jar").toString(),
            log = "engine.log",
        )

    assertNotEquals(0, process.awaitExit(TestTimeouts.processExit), output("engine.log"))
    assertTrue(output("engine.log").contains("ann"), output("engine.log"))
    assertFalse(output("engine.log").contains("tok-first-0123456789"), output("engine.log"))
    assertFalse(output("engine.log").contains("tok-second-0123456789"), output("engine.log"))
  }

  @Test
  fun `the packaged Engine opens its keystore, lists the aliases and masks a secret a run echoes`() {
    val database = PostgresTestContainer.newDatabase()
    migrate(database, dist.resolve("run-runtime"))
    val keystores = Keystores(Files.createTempDirectory(work, "keystore"))
    val file =
        keystores.pkcs12(
            "engine.p12",
            mapOf("Api-Key" to "marker-packaged-key-31"),
            "marker-packaged-storepass-31",
        )
    val passwordFile = keystores.passwordFile("marker-packaged-storepass-31", "engine.pw")
    startEngine(
        database,
        dist.resolve("run-runtime"),
        mapOf(
            "RUNLINE_KEYSTORE_PATH" to file.toString(),
            "RUNLINE_KEYSTORE_PASSWORD_FILE" to passwordFile.toString(),
        ),
    )

    val listed = get("/api/v1/secrets", ROOT)
    assertEquals(200, listed.statusCode(), listed.body())
    assertEquals(
        listOf("api-key"),
        json(listed)["secrets"]!!.jsonArray.map { it.jsonObject["alias"]!!.jsonPrimitive.content },
    )
    // A pipeline that fails with the key in its message, as a service that echoes it would make it.
    val runId =
        uploadAndRun(
            "echo",
            """throw new IllegalStateException("key marker-packaged-key-31 refused");""",
        )
    val ended = awaitState(runId, "FAILED")

    assertEquals(
        "key *** refused",
        ended["failure"]!!.jsonObject["message"]!!.jsonPrimitive.content,
    )
    for (said in listOf(listed.body(), ended.toString(), output("engine.log"))) {
      assertFalse(said.contains("marker-packaged-key-31"), said)
      assertFalse(said.contains("marker-packaged-storepass-31"), said)
    }
    assertTrue(output("engine.log").contains("key *** refused"), "the Engine's own log is masked")
  }

  @Test
  fun `the packaged Engine exits with a non-zero code and a category when the keystore password is wrong`() {
    val database = PostgresTestContainer.newDatabase()
    migrate(database, dist.resolve("run-runtime"))
    val keystores = Keystores(Files.createTempDirectory(work, "keystore"))
    val file = keystores.pkcs12("engine.p12", mapOf("a" to "value"))
    val process =
        launch(
            "the Engine with a wrong keystore password",
            environment(
                database,
                dist.resolve("run-runtime"),
                mapOf(
                    "RUNLINE_KEYSTORE_PATH" to file.toString(),
                    "RUNLINE_KEYSTORE_PASSWORD" to "marker-wrong-password-32",
                ),
            ),
            "-jar",
            dist.resolve("engine.jar").toString(),
            log = "engine.log",
        )

    assertNotEquals(0, process.awaitExit(TestTimeouts.processExit), output("engine.log"))
    assertTrue(output("engine.log").contains("wrong_password"), output("engine.log"))
    assertFalse(output("engine.log").contains("marker-wrong-password-32"), output("engine.log"))
    assertFalse(output("engine.log").contains(file.toString()), output("engine.log"))
  }

  /** The self-check command of the packaged jar, as a container runs it: exit code and output. */
  private fun healthCheck(
      vararg args: String,
      targetPort: Int = port,
      limit: Duration = Duration.ofSeconds(8),
  ): Pair<Int, String> {
    val log = "health-${healthChecks++}.log"
    val process =
        ManagedProcess.start(
                "the health check",
                listOf(
                    javaBin,
                    "-cp",
                    dist.resolve("engine.jar").toString(),
                    "dev.lawlan.runline.engine.HealthCheckKt",
                    *args,
                ),
                work.resolve(log),
                environment = mapOf("PORT" to targetPort.toString()),
                directory = work,
            )
            .also { processes += it }
    return process.awaitExit(limit) to output(log)
  }

  private var healthChecks = 0

  @Test
  fun `the health check command succeeds against a running Engine for live and for ready`() {
    val database = PostgresTestContainer.newDatabase()
    val runtime = dist.resolve("run-runtime")
    migrate(database, runtime)
    startEngine(database, runtime)

    assertEquals(0, healthCheck("live").first)
    assertEquals(0, healthCheck("ready").first)
  }

  @Test
  fun `the health check command fails when nothing listens`() {
    val (code, output) = healthCheck("ready")

    assertNotEquals(0, code, output)
  }

  @Test
  fun `the health check command fails within seconds when the Engine does not answer`() {
    StuckServer("").use { stuck ->
      val (code, output) = healthCheck("live", targetPort = stuck.port)

      assertNotEquals(0, code, output)
    }
  }

  @Test
  fun `the health check command fails for ready but not for live while the database is down`() {
    StoppablePostgres.startMigrated().use { postgres ->
      val runtime = dist.resolve("run-runtime")
      val engine = startEngine(postgres.database, runtime)
      assertEquals(0, healthCheck("ready").first)

      postgres.stop()

      awaitCondition(
          "ready to fail while the database is down",
          diagnostics = { engine.outputTail() },
      ) {
        healthCheck("ready").first != 0
      }
      assertEquals(0, healthCheck("live").first)
    }
  }

  @Test
  fun `the health check command names what it accepts when asked for anything else`() {
    val (code, output) = healthCheck("whatever")

    assertNotEquals(0, code)
    assertTrue(output.contains("live") && output.contains("ready"), output)
  }

  // ---- configuration through the environment (WI-18) ----

  @Test
  fun `the service name and the jar expansion limits come from the environment of the packaged Engine`() {
    val database = PostgresTestContainer.newDatabase()
    val runtime = dist.resolve("run-runtime")
    migrate(database, runtime)
    startEngine(
        database,
        runtime,
        mapOf(
            "OTEL_SERVICE_NAME" to "runline-engine-packaged",
            "UPLOAD_MAX_ENTRY_BYTES" to "1000000",
        ),
    )

    // Log lines carry the configured name (the same one traces and metrics get).
    assertTrue(
        output("engine.log").lines().any {
          it.contains("runline-engine-packaged") && it.contains("Responding at")
        },
        output("engine.log"),
    )

    // A real high-compression jar, and an ordinary one, against the limit set by the environment.
    val bomb =
        PipelineJars.build(
            work,
            "bomb.jar",
            mapOf("demo.Pipe" to PipelineJars.pipeline("demo.Pipe", "p")),
            mapOf("data/zeros.bin" to ByteArray(30_000_000)),
        )
    assertTrue(Files.size(bomb) < 200_000)
    val refused =
        send(
            "POST",
            "/api/v1/artifacts",
            ALICE,
            Files.readAllBytes(bomb),
            "application/octet-stream",
        )
    assertEquals(422, refused.statusCode(), refused.body())
    assertEquals("jar_entry_too_large", json(refused)["error"]!!.jsonPrimitive.content)
    val ordinary =
        send("POST", "/api/v1/artifacts", ALICE, jarBytes("ordinary"), "application/octet-stream")
    assertEquals(201, ordinary.statusCode(), ordinary.body())
  }

  @Test
  fun `the packaged Engine starts with the default allow list and keeps the administrator's changes across restarts`() {
    val database = PostgresTestContainer.newDatabase()
    val runtime = dist.resolve("run-runtime")
    migrate(database, runtime)
    val first = startEngine(database, runtime)

    val initial = json(get("/api/v1/allowlist", ROOT))
    assertEquals("1", initial["version"]!!.jsonPrimitive.content)
    val kinds = initial["entries"]!!.jsonArray.map { it.jsonObject["kind"]!!.jsonPrimitive.content }
    assertEquals(setOf("package", "class"), kinds.toSet())
    val added =
        send(
            "POST",
            "/api/v1/allowlist/entries",
            ROOT,
            """{"kind":"package","name":"com.acme"}""".toByteArray(),
            "application/json",
        )
    assertEquals(201, added.statusCode(), added.body())
    assertEquals(403, get("/api/v1/allowlist", ALICE).statusCode())

    first.terminate()
    first.awaitExit(TestTimeouts.processExit)
    // A different initial list in the environment is ignored once the list has been administered.
    startEngine(database, runtime, mapOf("ALLOWLIST_PACKAGES" to "kotlin"))

    val afterRestart = json(get("/api/v1/allowlist", ROOT))
    assertEquals("2", afterRestart["version"]!!.jsonPrimitive.content)
    val names =
        afterRestart["entries"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
    assertTrue("com.acme" in names, names.toString())
    assertTrue("java.lang" in names, names.toString())
  }

  private companion object {
    const val ALICE = "tok-alice-0123456789"
    const val ROOT = "tok-root-0123456789"

    /** What a shutdown may take beyond the grace time (04 "優雅關閉"). */
    val SHUTDOWN_WRAP_UP: Duration = Duration.ofSeconds(10)
  }
}
