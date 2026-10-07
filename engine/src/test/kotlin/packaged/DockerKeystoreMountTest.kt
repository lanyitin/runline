package dev.lawlan.runline.engine.packaged

import dev.lawlan.runline.engine.support.ComposeProject
import dev.lawlan.runline.engine.support.Keystores
import dev.lawlan.runline.engine.support.TestTimeouts
import dev.lawlan.runline.engine.support.TimedHttp
import java.io.InputStream
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.*
import kotlinx.serialization.json.*

/**
 * The keystore as Docker deploys it (WI-42): the real `deploy/docker/compose.yaml` with its
 * `compose.keystore.yaml`, the packaged Engine in a container and a real PostgreSQL in another. The
 * keystore is made with the real `keytool`, mounted read-only, and its password reaches the Engine
 * as a Docker secret. Needs Docker; without it the test fails, it is not skipped.
 */
class DockerKeystoreMountTest {
  private val repo = Path.of(System.getProperty("runline.repoRoot"))

  // The Docker host must see these directories to mount them, and a temporary directory of the
  // system is not always one it sees; the build directory of the repository is.
  private val work: Path =
      Files.createTempDirectory(
          Files.createDirectories(repo.resolve("engine/build/docker-keystore")),
          "run",
      )
  // Where the keystore is made (with the password files the helper writes beside it) and where it
  // is deployed: only the keystore goes to the second, as on a host.
  private val keystores = Keystores(Files.createDirectories(work.resolve("made")))
  private val keystoreDir = Files.createDirectories(work.resolve("keystore"))
  private val secretsDir = Files.createDirectories(work.resolve("secrets"))
  private val project = "wi42" + System.nanoTime().toString(36)
  private val port = ServerSocket(0).use { it.localPort }
  private val http = TimedHttp()
  private val admin = "tok-admin-for-the-keystore-test"
  private val keystorePassword = "keystore-pass-for-the-docker-test"
  private val passwordFile = secretsDir.resolve("keystore-password")

  private fun deployFile(name: String) = repo.resolve("deploy/docker/$name")

  private fun composeProject(restart: String? = null): ComposeProject {
    Files.writeString(
        work.resolve("env"),
        """
        POSTGRES_DB=runline
        POSTGRES_USER=runline
        POSTGRES_PASSWORD=keystore-test-db-password
        POSTGRES_URL=jdbc:postgresql://postgres:5432/runline
        API_TOKENS=ops:admin:$admin
        RUNLINE_MAX_CONCURRENT_RUNS=2
        RUNLINE_WORKSPACE_MAX_BYTES=1073741824
        RUNLINE_FAILED_RUN_RETENTION_SECONDS=3600
        RUNLINE_SHUTDOWN_GRACE_SECONDS=5
        OTEL_TRACES_EXPORTER=none
        OTEL_LOGS_EXPORTER=none
        OTEL_METRICS_EXPORTER=none
        KEYSTORE_HOST_DIR=$keystoreDir
        KEYSTORE_PASSWORD_HOST_FILE=$passwordFile
        RUNLINE_IMAGE_TAG=$project
        """
            .trimIndent(),
    )
    // The port of the real file is fixed; here the Engine is published on a free one. Its
    // environment file is this test's, not the .env that only an operator creates.
    val override = work.resolve("override.yaml")
    val restartLine = restart?.let { "    restart: !override \"$it\"\n" }.orEmpty()
    Files.writeString(
        override,
        "services:\n" +
            "  migrate:\n" +
            "    env_file: !override\n" +
            "      - ${work.resolve("env")}\n" +
            "  engine:\n" +
            "    env_file: !override\n" +
            "      - ${work.resolve("env")}\n" +
            "    ports: !override\n" +
            "      - \"127.0.0.1:$port:8080\"\n" +
            restartLine,
    )
    return ComposeProject(
        work,
        project,
        work.resolve("env"),
        listOf(deployFile("compose.yaml"), deployFile("compose.keystore.yaml"), override),
    )
  }

  private fun deployKeystore(secrets: Map<String, String>): Path =
      Files.copy(
          keystores.pkcs12("runline.p12", secrets, keystorePassword),
          keystoreDir.resolve("runline.p12"),
      )

  private lateinit var stack: ComposeProject

  @AfterTest
  fun tearDown() {
    if (this::stack.isInitialized) {
      stack.tearDown()
      stack.docker("image", "rm", "-f", "runline-engine:$project", timeoutMinutes = 3)
    }
    work.toFile().deleteRecursively()
  }

  private fun get(path: String) =
      http.send(
          HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
              .header("Authorization", "Bearer $admin")
              .GET()
      )

  private fun post(path: String) =
      http.send(
          HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
              .header("Authorization", "Bearer $admin")
              .POST(HttpRequest.BodyPublishers.noBody())
      )

  private fun json(response: HttpResponse<String>) =
      Json.parseToJsonElement(response.body()).jsonObject

  private fun aliases(): List<String> =
      json(get("/api/v1/secrets"))["secrets"]!!.jsonArray.map {
        it.jsonObject["alias"]!!.jsonPrimitive.content
      }

  private fun containsBytes(source: InputStream, needle: ByteArray): Boolean {
    val buffer = ByteArray(1 shl 20)
    var carried = 0
    while (true) {
      val read = source.read(buffer, carried, buffer.size - carried)
      if (read < 0) return false
      val filled = carried + read
      for (start in 0..filled - needle.size) {
        if (needle.indices.all { buffer[start + it] == needle[it] }) return true
      }
      carried = minOf(needle.size - 1, filled)
      System.arraycopy(buffer, filled - carried, buffer, 0, carried)
    }
  }

  @Test
  fun `the engine opens the mounted keystore read-only, keeps the password out of the image and reloads a replaced file`() {
    Files.writeString(passwordFile, "$keystorePassword\n")
    val file = deployKeystore(mapOf("db-main" to "placeholder-database-secret"))
    stack = composeProject()
    stack.compose("up", "--build", "-d", "--wait", "--wait-timeout", "300")

    assertEquals(listOf("db-main"), aliases())

    // The keystore directory is mounted read-only: nothing in the container can write to it.
    val write = stack.exec("engine", "touch", "/etc/runline/keystore/written-by-the-engine")
    assertNotEquals(0, write.exitCode, "the mount is writable:\n${write.output}")
    val mounts =
        stack
            .docker(
                "inspect",
                "-f",
                "{{range .Mounts}}{{.Destination}}={{.RW}};{{end}}",
                stack.containerId("engine"),
            )
            .output
            .trim()
    assertContains(mounts, "/etc/runline/keystore=false;")
    assertContains(mounts, "/run/secrets/runline_keystore_password=false;")

    // The password is neither in an image layer or its history nor in the container environment.
    val container = stack.containerId("engine")
    val environment = stack.docker("inspect", "-f", "{{json .Config.Env}}", container).output
    assertFalse(keystorePassword in environment, "the container environment holds the password")
    val history =
        stack.docker(
            "history",
            "--no-trunc",
            "--format",
            "{{.CreatedBy}}",
            "runline-engine:$project",
        )
    assertFalse(keystorePassword in history.output, "the image history holds the password")
    val save = ProcessBuilder("docker", "save", "runline-engine:$project").start()
    val inLayers = save.inputStream.use { containsBytes(it, keystorePassword.toByteArray()) }
    save.destroy()
    assertFalse(inLayers, "an image layer holds the password")

    // An update as the operating manual says: a copy, changed, put in place by an atomic rename.
    val copy = file.resolveSibling("runline.p12.new")
    Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING)
    keystores.importSecret(copy, "api-key", "placeholder-api-key", passwordFile)
    Files.move(copy, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    val reload = post("/api/v1/secrets/reload")
    assertEquals(200, reload.statusCode(), reload.body())
    assertEquals(2, json(reload)["aliases"]!!.jsonPrimitive.int, reload.body())
    assertEquals(listOf("api-key", "db-main"), aliases())
  }

  @Test
  fun `a wrong password stops the engine with a failure that does not say the password`() {
    Files.writeString(passwordFile, "not-the-password-of-the-keystore\n")
    deployKeystore(mapOf("db-main" to "placeholder-database-secret"))
    // No restart, so that the failed start stays to be looked at.
    stack = composeProject(restart = "no")
    stack.compose("up", "-d", "--wait", "postgres")
    stack.compose("up", "--build", "--no-deps", "--exit-code-from", "migrate", "migrate")
    stack.tryCompose("up", "-d", "--no-deps", "engine")
    val container = stack.containerId("engine")
    val exit =
        stack.docker("wait", container, timeoutMinutes = TestTimeouts.engineStart.toMinutes() + 1)
    assertNotEquals("0", exit.output.trim(), "the Engine started with the wrong password")
    val logs = stack.compose("logs", "--no-color", "engine")
    assertContains(logs, "wrong_password")
    assertFalse("not-the-password-of-the-keystore" in logs, "the log says the password")
    assertFalse(keystorePassword in logs, "the log says the password")
  }
}
