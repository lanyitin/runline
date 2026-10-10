package dev.lawlan.runline.engine.browserdeploy

import dev.lawlan.runline.engine.support.ComposeProject
import dev.lawlan.runline.engine.support.PipelineJars
import dev.lawlan.runline.engine.support.RunHarness
import dev.lawlan.runline.engine.support.TestTimeouts
import dev.lawlan.runline.engine.support.TimedHttp
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One deployment of the browser image made of the real files under `deploy/docker` (`compose.yaml`,
 * `compose.browser.yaml`) and the overrides a test adds: the packaged Engine in a container and a
 * real PostgreSQL in another. [engineOverride] and [extraServices] are YAML (mapping entries) added
 * to the Engine and next to it; [env] is what an operator's `.env` holds besides the database and
 * the tokens.
 */
class BrowserDeployment(
    name: String,
    private val engineOverride: String = "",
    private val extraServices: String = "",
    private val env: Map<String, String> = emptyMap(),
) : AutoCloseable {
  private val repo = Path.of(System.getProperty("runline.repoRoot"))

  // The Docker host must see the files it mounts; the build directory of the repository is seen.
  val work: Path =
      Files.createTempDirectory(
          Files.createDirectories(repo.resolve("engine/build/docker-browser")),
          "$name-",
      )
  private val port = ServerSocket(0).use { it.localPort }
  private val http = TimedHttp()
  val admin = "tok-admin-for-the-browser-test"
  val developer = "tok-dev-for-the-browser-test"

  val stack =
      ComposeProject(
          work,
          "wi1001" + System.nanoTime().toString(36),
          work.resolve("env"),
          listOf(
              repo.resolve("deploy/docker/compose.yaml"),
              repo.resolve("deploy/docker/compose.browser.yaml"),
              work.resolve("override.yaml"),
          ),
      )

  /** Builds and starts everything and waits until the Engine reports ready. */
  fun up(): BrowserDeployment {
    val variables =
        mapOf(
            "POSTGRES_DB" to "runline",
            "POSTGRES_USER" to "runline",
            "POSTGRES_PASSWORD" to "browser-test-db-password",
            "POSTGRES_URL" to "jdbc:postgresql://postgres:5432/runline",
            "API_TOKENS" to "dev:developer:$developer,ops:admin:$admin",
            "RUNLINE_MAX_CONCURRENT_RUNS" to "2",
            "RUNLINE_WORKSPACE_MAX_BYTES" to "1073741824",
            "RUNLINE_FAILED_RUN_RETENTION_SECONDS" to "3600",
            "RUNLINE_SHUTDOWN_GRACE_SECONDS" to "5",
            "OTEL_TRACES_EXPORTER" to "none",
            "OTEL_LOGS_EXPORTER" to "none",
            "OTEL_METRICS_EXPORTER" to "none",
        ) + env
    Files.writeString(
        work.resolve("env"),
        variables.entries.joinToString("\n") { "${it.key}=${it.value}" },
    )
    // The ports of the real file are fixed; here the Engine is published on a free one. Its
    // environment file is this test's, not the .env that only an operator creates.
    Files.writeString(
        work.resolve("override.yaml"),
        "services:\n" +
            "  migrate:\n    env_file: !override\n      - ${work.resolve("env")}\n" +
            "  engine:\n    env_file: !override\n      - ${work.resolve("env")}\n" +
            "    ports: !override\n      - \"127.0.0.1:$port:8080\"\n" +
            engineOverride.prependIndent("    ") +
            "\n" +
            extraServices.prependIndent("  ") +
            "\n",
    )
    stack.compose("up", "--build", "-d", "--wait", "--wait-timeout", "900", timeoutMinutes = 30)
    return this
  }

  /**
   * Starts the Engine's container anew with [more] added to its environment file, the way an
   * operator changes `.env` and runs `up` again; waits until it is ready.
   */
  fun restartEngineWith(more: Map<String, String>) {
    Files.writeString(
        work.resolve("env"),
        Files.readString(work.resolve("env")).trimEnd() +
            "\n" +
            more.entries.joinToString("\n") { "${it.key}=${it.value}" },
    )
    stack.compose(
        "up",
        "-d",
        "--force-recreate",
        "--no-deps",
        "--wait",
        "--wait-timeout",
        "300",
        "engine",
        timeoutMinutes = 10,
    )
  }

  override fun close() = stack.tearDown()

  /** A command in the Engine's container. */
  fun inEngine(vararg command: String) = stack.exec("engine", *command)

  /** A command in the Engine's container with [env] added to its environment. */
  fun inEngineWith(env: Map<String, String>, vararg command: String) =
      stack.tryCompose(
          "exec",
          "-T",
          *env.flatMap { (k, v) -> listOf("-e", "$k=$v") }.toTypedArray(),
          "engine",
          *command,
      )

  /** Everything the Engine's container has written so far. */
  fun logs(): String = stack.compose("logs", "--no-log-prefix", "engine")

  fun awaitLog(what: String, seconds: Long = 120, contains: (String) -> Boolean): String {
    val deadline = System.nanoTime() + seconds * 1_000_000_000
    var logs = ""
    while (System.nanoTime() < deadline) {
      logs = logs()
      if (contains(logs)) return logs
      Thread.sleep(1000)
    }
    fail("no $what in the Engine's log within $seconds seconds:\n$logs")
  }

  fun api(
      method: String,
      path: String,
      token: String,
      body: ByteArray = ByteArray(0),
      type: String = "application/json",
  ): HttpResponse<String> =
      http.send(
          HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
              .header("Authorization", "Bearer $token")
              .header("Content-Type", type)
              .method(method, HttpRequest.BodyPublishers.ofByteArray(body))
      )

  fun json(body: String) = Json.parseToJsonElement(body).jsonObject

  /** Defines a resource as the administrator; the settings are JSON. */
  fun defineResource(name: String, type: String, settings: String) {
    val defined =
        api(
            "POST",
            "/api/v1/resources",
            admin,
            """{"name":"$name","capacity":1,"type":"$type","settings":$settings}""".toByteArray(),
        )
    assertEquals(201, defined.statusCode(), defined.body())
  }

  /** What the Engine's check of the resource says: its `failure`, null when it passed. */
  fun failureOfCheck(name: String): String? {
    val checked = api("POST", "/api/v1/resources/$name/check", admin)
    assertEquals(200, checked.statusCode(), checked.body())
    val failure = json(checked.body())["failure"]
    return failure?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
  }

  /**
   * A `sqlite` resource `db` and a pipeline that writes a row to it and reports the journal mode;
   * made on first use. The hash of the pipeline's artifact.
   */
  val sqliteWriter: String by lazy {
    defineResource("db", "jdbc-pool", """{"kind":"sqlite","path":"digest.db"}""")
    val jar =
        PipelineJars.build(
            work,
            "sqlite-writer.jar",
            mapOf(
                "demo.SqliteWriter" to
                    PipelineJars.pipeline(
                        "demo.SqliteWriter",
                        "sqlite-writer",
                        RunHarness.usingTyped("db" to "jdbc-pool"),
                        "",
                        """
                        JdbcAccessor db = context.getAccessors().jdbcPool("db");
                        db.update("CREATE TABLE IF NOT EXISTS t (n INTEGER)");
                        db.update("INSERT INTO t VALUES (1)");
                        Object mode = db.query("PRAGMA journal_mode").maps().get(0).get("journal_mode");
                        Object rows = db.query("SELECT count(*) AS c FROM t").maps().get(0).get("c");
                        context.getFiles().writeText(FileScope.PIPELINE_SHARED, "outcome", mode + "|" + rows);
                        """
                            .trimIndent(),
                    )
            ),
        )
    val upload =
        api(
            "POST",
            "/api/v1/artifacts",
            developer,
            Files.readAllBytes(jar),
            "application/octet-stream",
        )
    assertEquals(201, upload.statusCode(), upload.body())
    json(upload.body())["contentHash"]!!.jsonPrimitive.content
  }

  /** Runs the pipeline [hash]/[pipeline] and waits for its end; returns the state it ended in. */
  fun runToEnd(hash: String, pipeline: String): String {
    val started =
        api(
            "POST",
            "/api/v1/runs",
            developer,
            """{"contentHash":"$hash","pipeline":"$pipeline"}""".toByteArray(),
        )
    assertEquals(201, started.statusCode(), started.body())
    val runId = json(started.body())["runId"]!!.jsonPrimitive.content
    val deadline = System.nanoTime() + TestTimeouts.condition.toNanos()
    var state = "no answer yet"
    while (System.nanoTime() < deadline) {
      state =
          json(api("GET", "/api/v1/runs/$runId", developer).body())["state"]!!.jsonPrimitive.content
      if (state in setOf("SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "INTERRUPTED")) {
        return state
      }
      Thread.sleep(200)
    }
    fail("run $runId is still $state")
  }
}
