package dev.lawlan.runline.engine.browserdeploy

import kotlin.test.*

/**
 * The browser image where something it relies on is not there (WI-1001): no Chrome and no display,
 * and a temporary directory the JVM may not load libraries from. Each test makes a deployment of
 * its own. Needs Docker and an amd64 image to run, like [BrowserDeploymentTest].
 */
class BrowserDeploymentDegradedTest {
  @Test
  fun `an Engine without a usable Chrome or display is ready all the same and says so in its log`() {
    BrowserDeployment(
            "degraded",
            env = mapOf("RUNLINE_VIRTUAL_DISPLAY" to "off", "CHROME_BIN" to "/nonexistent/chrome"),
        )
        .up()
        .use { deployment ->
          // `up` returned because the readiness probe of ADR-018, the Engine's own, said ready.
          val ready = deployment.api("GET", "/api/v1/health/ready", deployment.admin)
          assertEquals(200, ready.statusCode(), ready.body())

          val logs =
              deployment.awaitLog("the probe's word about the missing Chrome") {
                "browser-probe: WARNING: could not read the renderer" in it
              }
          assertTrue("virtual display not started" in logs, logs)
        }
  }

  @Test
  fun `a temporary directory the JVM may not execute from fails with a message that names the library and the directory, and a dedicated directory fixes it`() {
    BrowserDeployment(
            "noexec",
            engineOverride = "tmpfs:\n  - /tmp:noexec,mode=1777\n",
            env = mapOf("RUNLINE_VIRTUAL_DISPLAY" to "off", "RUNLINE_BROWSER_PROBE" to "off"),
        )
        .up()
        .use { deployment ->
          deployment.defineResource("db", "jdbc-pool", """{"kind":"sqlite","path":"digest.db"}""")

          assertNotNull(deployment.failureOfCheck("db"), "a library that cannot be loaded")
          val logs = deployment.logs()
          assertTrue("UnsatisfiedLinkError" in logs, logs)
          assertTrue(
              Regex("/tmp/sqlite-[^ ]*libsqlitejdbc\\.so").containsMatchIn(logs),
              "the file in the temporary directory is named:\n$logs",
          )
          assertTrue("failed to map segment from shared object" in logs, logs)

          // The fix an operator is told: a directory of their own that allows execution.
          deployment.restartEngineWith(
              mapOf("JDK_JAVA_OPTIONS" to "-Dorg.sqlite.tmpdir=/var/lib/runline/runs")
          )
          assertNull(deployment.failureOfCheck("db"))
        }
  }
}
