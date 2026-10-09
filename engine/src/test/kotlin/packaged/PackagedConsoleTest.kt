package dev.lawlan.runline.engine.packaged

import dev.lawlan.runline.accessors.support.TestDirectories
import dev.lawlan.runline.engine.config.DatabaseConfig
import dev.lawlan.runline.engine.support.ManagedProcess
import dev.lawlan.runline.engine.support.PostgresTestContainer
import dev.lawlan.runline.engine.support.TestTimeouts
import dev.lawlan.runline.engine.support.TimedHttp
import java.net.ServerSocket
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.*

/**
 * The Console in the Engine as it is deployed (WI-31, ADR-015): the real build of the frontend, in
 * the real `engine.jar`, served by the Engine as its own process. What is served is what the build
 * made, not what a test resource says.
 */
class PackagedConsoleTest {
  private val dist = Path.of(System.getProperty("runline.dist"))
  private val javaBin = System.getProperty("runline.java")
  private val work: Path = TestDirectories.forThisTest("packaged-console")
  private val http = TimedHttp()
  private val probe = TimedHttp(requestTimeout = TestTimeouts.readinessProbe)
  private val processes = mutableListOf<ManagedProcess>()
  private val port = ServerSocket(0).use { it.localPort }
  private val base = "http://localhost:$port"

  @AfterTest
  fun stop() {
    processes.forEach { it.close() }
  }

  private fun launch(
      description: String,
      env: Map<String, String>,
      log: String,
      vararg args: String,
  ) =
      ManagedProcess.start(description, listOf(javaBin) + args, work.resolve(log), env, work).also {
        processes += it
      }

  private fun environment(database: DatabaseConfig, runtimeDir: Path) =
      mapOf(
          "PORT" to port.toString(),
          "POSTGRES_URL" to database.url,
          "POSTGRES_USER" to database.user,
          "POSTGRES_PASSWORD" to database.password,
          "API_TOKENS" to "alice:developer:tok-alice-0123456789",
          "RUNLINE_SHARED_ROOT" to work.resolve("shared").toString(),
          "RUNLINE_RUN_ROOT" to work.resolve("runs").toString(),
          "RUNLINE_RESOURCE_ROOT" to work.resolve("resources").toString(),
          "RUNLINE_WORKSPACE_MAX_BYTES" to "1000000",
          "RUNLINE_FAILED_RUN_RETENTION_SECONDS" to "3600",
          "RUNLINE_MAX_CONCURRENT_RUNS" to "2",
          "RUNLINE_RUNTIME_DIR" to runtimeDir.toString(),
          "OTEL_TRACES_EXPORTER" to "none",
          "OTEL_LOGS_EXPORTER" to "none",
          "OTEL_METRICS_EXPORTER" to "none",
      )

  private fun startEngine() {
    val database = PostgresTestContainer.newDatabase()
    val runtime = dist.resolve("run-runtime")
    val env = environment(database, runtime)
    val migration =
        launch(
            "the migration process",
            env,
            "migrate.log",
            "-cp",
            dist.resolve("engine.jar").toString(),
            "dev.lawlan.runline.engine.db.MigrateKt",
        )
    assertEquals(0, migration.awaitExit(TestTimeouts.migration), migration.output())
    val engine =
        launch(
            "the packaged Engine",
            env,
            "engine.log",
            "-jar",
            dist.resolve("engine.jar").toString(),
        )
    val deadline = System.nanoTime() + TestTimeouts.engineStart.toNanos()
    while (System.nanoTime() < deadline) {
      check(engine.process.isAlive) { "the Engine exited early:\n${engine.output()}" }
      if (runCatching { probe.get("$base/openapi").statusCode() }.getOrNull() == 200) return
      Thread.sleep(200)
    }
    fail("the Engine did not come up:\n${engine.outputTail()}")
  }

  private fun get(path: String): HttpResponse<String> = http.get("$base$path")

  private fun entriesOf(jar: Path): List<String> =
      ZipFile(jar.toFile()).use { zip -> zip.entries().toList().map { it.name } }

  @Test
  fun `engine jar holds the built Console and the run runtime holds none of it`() {
    val inEngine = entriesOf(dist.resolve("engine.jar"))
    assertTrue("console/index.html" in inEngine, "no entry page in engine.jar")
    assertTrue(inEngine.any { it.startsWith("console/assets/") && it.endsWith(".js") })

    val runtimeJars =
        Files.list(dist.resolve("run-runtime"))
            .use { files -> files.toList() }
            .filter { it.toString().endsWith(".jar") }
    assertTrue(runtimeJars.isNotEmpty())
    for (jar in runtimeJars) {
      assertEquals(emptyList(), entriesOf(jar).filter { it.startsWith("console/") }, jar.toString())
    }
  }

  @Test
  fun `the Engine serves the entry page and the files the build made`() {
    startEngine()

    val entry = get("/")
    assertEquals(200, entry.statusCode(), entry.body())
    assertTrue(entry.headers().firstValue("Content-Type").get().startsWith("text/html"))
    assertEquals("no-cache", entry.headers().firstValue("Cache-Control").get())
    assertTrue(
        entry.headers().firstValue("Content-Security-Policy").get().contains("default-src 'self'")
    )
    val script = Regex("src=\"(/assets/[^\"]+\\.js)\"").find(entry.body())?.groupValues?.get(1)
    assertNotNull(script, "the entry page references no script under /assets/: ${entry.body()}")

    val asset = get(script)
    assertEquals(200, asset.statusCode())
    assertEquals(
        "public, max-age=31536000, immutable",
        asset.headers().firstValue("Cache-Control").get(),
    )
    assertEquals("nosniff", asset.headers().firstValue("X-Content-Type-Options").get())
    assertTrue(asset.body().isNotEmpty())
  }

  @Test
  fun `paths of the application get the entry page, files that are none and the API do not`() {
    startEngine()
    val entry = get("/").body()

    for (path in listOf("/pipelines/etl/runs/7", "/assets", "/login")) {
      assertEquals(entry, get(path).body(), path)
    }
    assertEquals(404, get("/nothing.js").statusCode())
    assertEquals(404, get("/assets/nothing-abc.js").statusCode())
    for (path in listOf("/api/v1/nothing", "/api", "/openapi/nothing")) {
      val response = get(path)
      assertEquals(404, response.statusCode(), path)
      assertNotEquals(entry, response.body(), path)
    }
    assertEquals(401, get("/api/v1/runs").statusCode())
    assertEquals(200, get("/api/v1/info").statusCode())
  }
}
