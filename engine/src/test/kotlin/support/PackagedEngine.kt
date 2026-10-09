package dev.lawlan.runline.engine.support

import dev.lawlan.runline.engine.config.DatabaseConfig
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlinx.serialization.json.*

/**
 * The Engine as it is deployed, for the tests of the packaged Engine that drive it through its API
 * (WI-51): `engine.jar` as its own process with the run runtime in a directory of its own, a new
 * migrated database on the real PostgreSQL, everything given through environment variables, and the
 * one-off migration process from the same jar. Every answer the Engine gives through [call] is kept
 * in [exchanges], so that a test can search all of them afterwards.
 */
class PackagedEngine(
    /** A directory of the test's own: the Engine's directories, its output and the jars. */
    val work: Path,
) : AutoCloseable {
  private val dist = Path.of(System.getProperty("runline.dist"))
  private val javaBin = System.getProperty("runline.java")
  private val http = TimedHttp()
  private val probe = TimedHttp(requestTimeout = TestTimeouts.readinessProbe)
  private val processes = mutableListOf<ManagedProcess>()
  private var starts = 0
  private var jars = 0

  val port: Int = ServerSocket(0).use { it.localPort }
  val base = "http://localhost:$port"
  val database: DatabaseConfig = PostgresTestContainer.newDatabase()
  val sharedRoot: Path = work.resolve("shared")
  val runRoot: Path = work.resolve("runs")
  val resourceRoot: Path = Files.createDirectories(work.resolve("resources"))

  /** One answer of the Engine: what was asked and everything that came back. */
  class Exchange(val request: String, val status: Int, val headers: String, val body: String) {
    override fun toString() = "$request -> $status $headers $body"
  }

  /** Every answer the Engine gave through [call], in order. */
  val exchanges: MutableList<Exchange> = CopyOnWriteArrayList()

  /** The Engine process most recently started. */
  lateinit var engine: ManagedProcess
    private set

  private fun environment(extra: Map<String, String>) =
      mapOf(
          "PORT" to port.toString(),
          "POSTGRES_URL" to database.url,
          "POSTGRES_USER" to database.user,
          "POSTGRES_PASSWORD" to database.password,
          "API_TOKENS" to "alice:developer:$ALICE,root:admin:$ROOT",
          "RUNLINE_SHARED_ROOT" to sharedRoot.toString(),
          "RUNLINE_RUN_ROOT" to runRoot.toString(),
          "RUNLINE_RESOURCE_ROOT" to resourceRoot.toString(),
          "RUNLINE_WORKSPACE_MAX_BYTES" to "10000000",
          "RUNLINE_FAILED_RUN_RETENTION_SECONDS" to "3600",
          "RUNLINE_MAX_CONCURRENT_RUNS" to "8",
          "RUNLINE_RUNTIME_DIR" to dist.resolve("run-runtime").toString(),
          "RUNLINE_SHUTDOWN_GRACE_SECONDS" to "5",
          "OTEL_TRACES_EXPORTER" to "none",
          "OTEL_LOGS_EXPORTER" to "none",
          "OTEL_METRICS_EXPORTER" to "none",
      ) + extra

  private fun launch(
      description: String,
      log: String,
      env: Map<String, String>,
      args: List<String>,
  ) =
      ManagedProcess.start(
              description,
              listOf(javaBin) + args,
              work.resolve(log),
              environment = env,
              directory = work,
          )
          .also { processes += it }

  /** Migrates the database with the one-off migration process of the packaged jar. */
  fun migrate() {
    val process =
        launch(
            "the migration process",
            "migrate.log",
            environment(emptyMap()),
            listOf(
                "-cp",
                dist.resolve("engine.jar").toString(),
                "dev.lawlan.runline.engine.db.MigrateKt",
            ),
        )
    assertEquals(0, process.awaitExit(TestTimeouts.migration), output("migrate.log"))
  }

  /** Starts the Engine with [extra] on top of the usual environment and waits until it answers. */
  fun start(extra: Map<String, String> = emptyMap()): ManagedProcess {
    val log = "engine-${starts++}.log"
    engine =
        launch(
            "the packaged Engine",
            log,
            environment(extra),
            listOf("-jar", dist.resolve("engine.jar").toString()),
        )
    val deadline = System.nanoTime() + TestTimeouts.engineStart.toNanos()
    while (System.nanoTime() < deadline) {
      check(engine.process.isAlive) { "the Engine exited early:\n${engine.output()}" }
      if (runCatching { probe.get("$base/api/v1/health/ready").statusCode() }.getOrNull() == 200) {
        return engine
      }
      Thread.sleep(200)
    }
    fail("the Engine did not come up in time:\n${engine.outputTail()}")
  }

  /** Stops the Engine as a platform does (SIGTERM) and waits for it to end. */
  fun stop() {
    engine.terminate()
    engine.awaitExit(TestTimeouts.processExit)
  }

  private fun output(log: String) = Files.readString(work.resolve(log))

  /** Everything every Engine process started here has written so far. */
  fun engineOutput(): String = (0 until starts).joinToString("\n") { output("engine-$it.log") }

  /** The end of the current Engine's output, for the message of a failure. */
  fun tail(): String = engine.outputTail(60)

  /** Sends a request to the Engine; the answer is kept in [exchanges]. */
  fun call(
      method: String,
      path: String,
      token: String? = ROOT,
      body: String? = null,
  ): HttpResponse<String> {
    val request =
        HttpRequest.newBuilder(URI("$base$path")).also { b ->
          token?.let { b.header("Authorization", "Bearer $it") }
          if (body != null) b.header("Content-Type", "application/json")
          b.method(
              method,
              body?.let { HttpRequest.BodyPublishers.ofString(it) }
                  ?: HttpRequest.BodyPublishers.noBody(),
          )
        }
    return http.send(request).also { remember("$method $path", it) }
  }

  private fun remember(request: String, response: HttpResponse<String>) {
    exchanges +=
        Exchange(request, response.statusCode(), "${response.headers().map()}", response.body())
  }

  fun json(response: HttpResponse<String>): JsonObject =
      Json.parseToJsonElement(response.body()).jsonObject

  /** Asks [path] and expects [status]; returns the body as JSON. */
  fun expect(status: Int, method: String, path: String, body: String? = null): JsonObject {
    val response = call(method, path, ROOT, body)
    assertEquals(status, response.statusCode(), "$method $path: ${response.body()}")
    return if (response.body().isEmpty()) JsonObject(emptyMap()) else json(response)
  }

  /**
   * Compiles the pipeline [name] (Java statements [body], annotation members [declaration] after
   * the name, class members [members]), uploads it as alice, lets it run unsafe whatever its
   * verdict, and returns the content hash.
   */
  fun upload(name: String, declaration: String, body: String, members: String = ""): String {
    val fqcn = "demo.P${jars}x${name.replace(Regex("[^A-Za-z0-9]"), "")}"
    val jar =
        PipelineJars.build(
            work,
            "$name-${jars++}.jar",
            mapOf(fqcn to PipelineJars.pipeline(fqcn, name, declaration, members, body)),
        )
    val upload =
        http.send(
            HttpRequest.newBuilder(URI("$base/api/v1/artifacts"))
                .header("Authorization", "Bearer $ALICE")
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(Files.readAllBytes(jar)))
        )
    remember("POST /api/v1/artifacts", upload)
    assertEquals(201, upload.statusCode(), upload.body())
    val hash = json(upload)["contentHash"]!!.jsonPrimitive.content
    expect(
        200,
        "PUT",
        "/api/v1/definitions/$hash/$name/unsafe-execution",
        """{"allow":true}""",
    )
    return hash
  }

  /** Starts a run of [name] in the version [hash] as alice; returns the run's id. */
  fun startRun(hash: String, name: String): String {
    val run = call("POST", "/api/v1/runs", ALICE, """{"contentHash":"$hash","pipeline":"$name"}""")
    assertEquals(201, run.statusCode(), run.body())
    return json(run)["runId"]!!.jsonPrimitive.content
  }

  /** Waits until the run is in one of [states]. */
  fun awaitState(runId: String, vararg states: String): JsonObject {
    val deadline = System.nanoTime() + TestTimeouts.condition.toNanos()
    var last = "no answer yet"
    while (System.nanoTime() < deadline) {
      val run = json(call("GET", "/api/v1/runs/$runId"))
      last = run["state"]!!.jsonPrimitive.content
      if (last in states) return run
      Thread.sleep(100)
    }
    fail("run $runId did not reach ${states.toList()} (last $last):\n${tail()}")
  }

  /** Waits for the run to end, in any of the ways a run ends. */
  fun awaitEnd(runId: String): JsonObject = awaitState(runId, *ENDED)

  /** Uploads, starts and waits for the end of a pipeline; returns the run as the API says it. */
  fun runToEnd(name: String, declaration: String, body: String, members: String = ""): JsonObject =
      awaitEnd(startRun(upload(name, declaration, body, members), name))

  /** The lines of a run's log, as the API gives them. */
  fun logLines(runId: String): List<String> =
      json(call("GET", "/api/v1/runs/$runId/log"))["entries"]!!.jsonArray.map {
        it.jsonObject["line"]!!.jsonPrimitive.content
      }

  /** A file of [pipeline]'s shared directory. */
  fun shared(pipeline: String, file: String): Path = sharedRoot.resolve(pipeline).resolve(file)

  override fun close() {
    val failures = processes.mapNotNull { runCatching { it.close() }.exceptionOrNull() }
    failures.firstOrNull()?.let { throw it }
  }

  companion object {
    const val ALICE = "tok-alice-0123456789"
    const val ROOT = "tok-root-0123456789"
    val ENDED = arrayOf("SUCCEEDED", "FAILED", "CANCELLED", "INTERRUPTED", "TIMED_OUT")

    /** Both file scopes writable, nothing else reachable. */
    const val FILES =
        "files = {@FileAccess(scope = FileScope.PIPELINE_SHARED, mode = FileMode.READ_WRITE)," +
            " @FileAccess(scope = FileScope.RUN_PRIVATE, mode = FileMode.READ_WRITE)}, " +
            "network = @AccessLimit(allow = {}), processes = @AccessLimit(allow = {})"

    /** A declaration of resources with the type the pipeline expects of each (name to type). */
    fun typed(vararg types: Pair<String, String>) =
        FILES +
            ", typedResources = {" +
            types.joinToString { (n, t) -> "@TypedResource(name = \"$n\", type = \"$t\")" } +
            "}"
  }
}
