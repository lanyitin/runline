package dev.lawlan.runline.engine.browserdeploy

import dev.lawlan.runline.accessors.tls.TestPki
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.*
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance

/**
 * The Engine as deployed for pipelines that drive a browser (WI-1001): the real
 * `deploy/docker/compose.yaml` with `compose.browser.yaml`, the packaged Engine in the browser
 * image (linux/amd64, a real Google Chrome) and a real PostgreSQL in another container. Needs
 * Docker and an amd64 image to run (on arm64, Rosetta); without them the tests fail, they are not
 * skipped.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BrowserDeploymentTest {
  private lateinit var deployment: BrowserDeployment

  @BeforeAll
  fun deploy() {
    // Two HTTPS services in the network of the Engine: the one of the operator's private CA, which
    // the deployment is configured to trust, and one of a CA nobody told the Engine about.
    val pki = TestPki(Files.createTempDirectory("pki"))
    val homelabCa = pki.authority("Homelab Root CA")
    val strangerCa = pki.authority("Stranger Root CA")
    val tls = Files.createDirectories(repoBuildDir().resolve("tls-" + System.nanoTime()))
    Files.createDirectories(tls.resolve("trusted-ca"))
    Files.copy(pki.export(homelabCa), tls.resolve("trusted-ca/homelab-root-ca.pem"))
    val homelab =
        serve(tls, pki, "homelab", pki.issue(homelabCa, "homelab.test", listOf("dns:homelab.test")))
    val stranger =
        serve(
            tls,
            pki,
            "stranger",
            pki.issue(strangerCa, "stranger.test", listOf("dns:stranger.test")),
        )
    deployment =
        BrowserDeployment(
                "browser",
                extraServices = homelab + stranger,
                env = mapOf("TRUSTED_CA_HOST_DIR" to tls.resolve("trusted-ca").toString()),
            )
            .up()
  }

  @AfterAll fun remove() = deployment.close()

  private fun repoBuildDir() =
      Files.createDirectories(
          java.nio.file.Path.of(System.getProperty("runline.repoRoot"))
              .resolve("engine/build/docker-browser")
      )

  /** A real nginx serving TLS with [identity]; reachable as `NAME.test` from the Engine. */
  private fun serve(
      dir: java.nio.file.Path,
      pki: TestPki,
      name: String,
      identity: TestPki.Issued,
  ): String {
    val own = Files.createDirectories(dir.resolve(name))
    Files.copy(pki.chainPem(identity), own.resolve("server.pem"))
    Files.copy(pki.serverKeyPem(identity), own.resolve("server.key"))
    own.resolve("default.conf")
        .writeText(
            """
            server {
              listen 443 ssl;
              ssl_certificate /etc/nginx/tls/server.pem;
              ssl_certificate_key /etc/nginx/tls/server.key;
              location / { default_type application/json; return 200 '{"data":[]}'; }
            }
            """
                .trimIndent()
        )
    return """
        $name:
          image: nginx:1.27-alpine
          networks:
            default:
              aliases: ["$name.test"]
          volumes:
            - $own/server.pem:/etc/nginx/tls/server.pem:ro
            - $own/server.key:/etc/nginx/tls/server.key:ro
            - $own/default.conf:/etc/nginx/conf.d/default.conf:ro

        """
        .trimIndent() + "\n"
  }

  @Test
  fun `the container has a real Chrome, the display DISPLAY names, an init as PID 1 and an unprivileged account`() {
    val chrome = deployment.inEngine("google-chrome-stable", "--version")
    assertEquals(0, chrome.exitCode, chrome.output)
    assertTrue(chrome.output.startsWith("Google Chrome "), chrome.output)

    assertEquals(":99", deployment.inEngine("printenv", "DISPLAY").output.trim())
    assertEquals(
        0,
        deployment.inEngine("test", "-S", "/tmp/.X11-unix/X99").exitCode,
        "the X socket of :99",
    )

    val init = deployment.inEngine("cat", "/proc/1/comm").output.trim()
    assertNotEquals("java", init, "the JVM must not be PID 1: it does not reap Chrome's orphans")
    assertEquals("10001", deployment.inEngine("id", "-u").output.trim())
  }

  @Test
  fun `a container without a GPU starts and says in its log that the renderer is software`() {
    val logs =
        deployment.awaitLog("warning about software rendering") {
          "browser-probe: WARNING: software rendering" in it
        }

    assertTrue(
        Regex("renderer='[^']*llvmpipe", RegexOption.IGNORE_CASE).containsMatchIn(logs),
        logs,
    )
    assertTrue("/dev/dri=absent" in logs, logs)
  }

  @Test
  fun `the probe fails when hardware rendering is required and the renderer is software, and not otherwise`() {
    val required =
        deployment.inEngineWith(
            mapOf("CHROME_REQUIRE_HARDWARE_RENDERING" to "true"),
            "runline-browser-probe",
        )
    assertEquals(3, required.exitCode, required.output)
    assertTrue("hardware rendering is required" in required.output, required.output)

    val optional = deployment.inEngine("runline-browser-probe")
    assertEquals(0, optional.exitCode, optional.output)
    assertTrue("software rendering" in optional.output, optional.output)
  }

  @Test
  fun `the Engine trusts the private CA the deployment is given and still refuses a service of any other`() {
    deployment.defineResource(
        "homelab",
        "openai-compatible",
        """{"baseUrl":"https://homelab.test"}""",
    )
    deployment.defineResource(
        "stranger",
        "openai-compatible",
        """{"baseUrl":"https://stranger.test"}""",
    )

    assertNull(deployment.failureOfCheck("homelab"), "the private CA is trusted")
    assertEquals("trust_failed", deployment.failureOfCheck("stranger"))
  }

  @Test
  fun `a sqlite resource in WAL mode takes the writes of ten runs one after the other`() {
    val writer = deployment.sqliteWriter

    repeat(10) { n ->
      assertEquals("SUCCEEDED", deployment.runToEnd(writer, "sqlite-writer"), "run ${n + 1}")
    }

    val outcome = deployment.inEngine("cat", "/var/lib/runline/shared/sqlite-writer/outcome")
    assertEquals("wal|10", outcome.output.trim(), outcome.output)
  }

  @Test
  fun `the Engine loads its native libraries, Netty's and SQLite's, without the JVM warning about restricted methods`() {
    assertEquals("SUCCEEDED", deployment.runToEnd(deployment.sqliteWriter, "sqlite-writer"))

    val logs = deployment.logs()
    assertFalse("restricted method" in logs, logs)
    assertFalse("--enable-native-access" in logs, logs)
  }
}
