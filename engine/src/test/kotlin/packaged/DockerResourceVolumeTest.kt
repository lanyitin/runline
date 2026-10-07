package dev.lawlan.runline.engine.packaged

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
import kotlin.test.*
import kotlinx.serialization.json.*

/**
 * The resource root as Docker deploys it (WI-43): the real `deploy/docker/compose.yaml` and
 * Dockerfile, the packaged Engine in a container, a real PostgreSQL in another. A file written
 * through a file resource lands in the mounted root, writable by the Engine's unprivileged account,
 * and is still there when the Engine's container is made anew. Needs Docker; without it the test
 * fails, it is not skipped.
 */
class DockerResourceVolumeTest {
  private val repo = Path.of(System.getProperty("runline.repoRoot"))
  private val dist = Path.of(System.getProperty("runline.dist"))
  private val work: Path = Files.createTempDirectory("docker-resource-volume")
  private val project = "wi43" + System.nanoTime().toString(36)
  private val port = ServerSocket(0).use { it.localPort }
  private val http = TimedHttp()
  private val admin = "tok-admin-for-the-volume-test"
  private val developer = "tok-dev-for-the-volume-test"

  private val stack by lazy {
    ComposeProject(
        work,
        project,
        work.resolve("env"),
        listOf(repo.resolve("deploy/docker/compose.yaml"), work.resolve("override.yaml")),
    )
  }

  private fun compose(vararg args: String, timeoutMinutes: Long = 10): String =
      stack.compose(*args, timeoutMinutes = timeoutMinutes)

  @BeforeTest
  fun configure() {
    Files.writeString(
        work.resolve("env"),
        """
        POSTGRES_DB=runline
        POSTGRES_USER=runline
        POSTGRES_PASSWORD=volume-test-password
        POSTGRES_URL=jdbc:postgresql://postgres:5432/runline
        API_TOKENS=dev:developer:$developer,ops:admin:$admin
        RUNLINE_MAX_CONCURRENT_RUNS=2
        RUNLINE_WORKSPACE_MAX_BYTES=1073741824
        RUNLINE_FAILED_RUN_RETENTION_SECONDS=3600
        RUNLINE_SHUTDOWN_GRACE_SECONDS=5
        OTEL_TRACES_EXPORTER=none
        OTEL_LOGS_EXPORTER=none
        OTEL_METRICS_EXPORTER=none
        """
            .trimIndent(),
    )
    // The ports of the real file are fixed; here the Engine is published on a free one. Its
    // environment file is this test's, not the .env that only an operator creates.
    Files.writeString(
        work.resolve("override.yaml"),
        """
        services:
          migrate:
            env_file: !override
              - ${work.resolve("env")}
          engine:
            env_file: !override
              - ${work.resolve("env")}
            ports: !override
              - "127.0.0.1:$port:8080"
        """
            .trimIndent(),
    )
  }

  @AfterTest
  fun tearDown() {
    runCatching { compose("down", "-v", "--remove-orphans", timeoutMinutes = 3) }
  }

  private fun send(method: String, path: String, token: String, body: ByteArray, type: String) =
      http.send(
          HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
              .header("Authorization", "Bearer $token")
              .header("Content-Type", type)
              .method(method, HttpRequest.BodyPublishers.ofByteArray(body))
      )

  private fun json(response: HttpResponse<String>) =
      Json.parseToJsonElement(response.body()).jsonObject

  private fun inEngine(vararg command: String) = compose("exec", "-T", "engine", *command).trim()

  @Test
  fun `a file written through a file resource lands in the mounted root and outlives the container`() {
    compose("up", "--build", "-d", "--wait", "--wait-timeout", "300")

    val whoami = inEngine("id", "-u")
    assertEquals("10001", whoami, "the Engine runs as its unprivileged account")
    val define =
        send(
            "POST",
            "/api/v1/resources",
            admin,
            """{"name":"audit","capacity":1,"type":"file","settings":{"path":"audit/log.txt"}}"""
                .toByteArray(),
            "application/json",
        )
    assertEquals(201, define.statusCode(), define.body())
    val jar =
        PipelineJars.build(
            work,
            "writer.jar",
            mapOf(
                "demo.Writer" to
                    PipelineJars.pipeline(
                        "demo.Writer",
                        "writer",
                        RunHarness.usingTyped("audit" to "file"),
                        "",
                        """context.getAccessors().file("audit").appendText("written in a container;");""",
                    )
            ),
        )
    val upload =
        send(
            "POST",
            "/api/v1/artifacts",
            developer,
            Files.readAllBytes(jar),
            "application/octet-stream",
        )
    assertEquals(201, upload.statusCode(), upload.body())
    val hash = json(upload)["contentHash"]!!.jsonPrimitive.content
    val run =
        send(
            "POST",
            "/api/v1/runs",
            developer,
            """{"contentHash":"$hash","pipeline":"writer"}""".toByteArray(),
            "application/json",
        )
    assertEquals(201, run.statusCode(), run.body())
    val runId = json(run)["runId"]!!.jsonPrimitive.content
    awaitSucceeded(runId)

    assertEquals(
        "written in a container;",
        inEngine("cat", "/var/lib/runline/resources/audit/log.txt"),
    )

    // The container is made anew; what the volume holds is not.
    compose(
        "up",
        "-d",
        "--force-recreate",
        "--no-deps",
        "--wait",
        "--wait-timeout",
        "300",
        "engine",
    )

    assertEquals(
        "written in a container;",
        inEngine("cat", "/var/lib/runline/resources/audit/log.txt"),
    )
    val check =
        send("POST", "/api/v1/resources/audit/check", admin, ByteArray(0), "application/json")
    assertEquals(200, check.statusCode(), check.body())
    assertEquals(JsonPrimitive(true), json(check)["ok"], check.body())
  }

  private fun awaitSucceeded(runId: String) {
    val deadline = System.nanoTime() + TestTimeouts.condition.toNanos()
    var state = "no answer yet"
    while (System.nanoTime() < deadline) {
      state =
          json(http.get("http://127.0.0.1:$port/api/v1/runs/$runId", admin))["state"]!!
              .jsonPrimitive
              .content
      if (state == "SUCCEEDED") return
      check(state !in setOf("FAILED", "CANCELLED", "TIMED_OUT", "INTERRUPTED")) { "run is $state" }
      Thread.sleep(200)
    }
    fail("run $runId is $state")
  }
}
