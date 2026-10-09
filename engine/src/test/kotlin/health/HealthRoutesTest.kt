package dev.lawlan.runline.engine.health

import dev.lawlan.runline.accessors.support.TestDirectories
import dev.lawlan.runline.engine.support.StoppablePostgres
import dev.lawlan.runline.engine.support.TestRuntime
import dev.lawlan.runline.engine.support.awaitConditionSuspending
import dev.lawlan.runline.engine.support.configureEngine
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.testing.*
import java.nio.file.Files
import java.time.Duration
import kotlin.test.*
import kotlinx.serialization.json.*

/**
 * `GET /api/v1/health/live` and `GET /api/v1/health/ready` (WI-29, ADR-018) on the real application
 * with a real PostgreSQL.
 */
class HealthRoutesTest {
  private companion object {
    // Shorter than the 60 s a test body is given, so that a wait that fails says what it waited for
    // instead of the test body being cut off.
    val PROBE_WAIT: Duration = Duration.ofSeconds(20)
  }

  private suspend fun HttpResponse.json(): JsonObject =
      Json.parseToJsonElement(bodyAsText()).jsonObject

  @Test
  fun `live needs no token and says only that the process is up`() = testApplication {
    configureEngine()

    val response = client.get("/api/v1/health/live")

    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals("""{"status":"up"}""", response.bodyAsText())
    assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
  }

  @Test
  fun `ready needs no token and, when everything is in order, says ready`() = testApplication {
    configureEngine()
    startApplication()

    val response = client.get("/api/v1/health/ready")

    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals("""{"status":"ready"}""", response.bodyAsText())
    assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
  }

  private suspend fun ApplicationTestBuilder.readyStatus() =
      client.get("/api/v1/health/ready").status

  @Test
  fun `a database that is gone makes ready 503 and not live, and ready recovers with it`() =
      testApplication {
        StoppablePostgres.startMigrated().use { postgres ->
          configureEngine(postgres.database)
          startApplication()
          assertEquals(HttpStatusCode.OK, readyStatus())

          postgres.stop()
          awaitConditionSuspending("ready to turn 503 with the database stopped", PROBE_WAIT) {
            readyStatus() == HttpStatusCode.ServiceUnavailable
          }

          val ready = client.get("/api/v1/health/ready")
          assertEquals(
              """{"status":"not_ready","checks":{"startup":"ok","database":"failed","runtime":"ok","shutdown":"ok"}}""",
              ready.bodyAsText(),
          )
          assertEquals(HttpStatusCode.OK, client.get("/api/v1/health/live").status)

          postgres.start()
          awaitConditionSuspending("ready to turn 200 with the database back", PROBE_WAIT) {
            readyStatus() == HttpStatusCode.OK
          }
        }
      }

  @Test
  fun `a run runtime that has lost a jar makes ready 503 and not live, and ready recovers when it is back`() =
      testApplication {
        val runtime = TestDirectories.forThisTest("run-runtime")
        TestRuntime.jars.forEach { Files.copy(it, runtime.resolve(it.fileName.toString())) }
        configureEngine(overrides = mapOf("runs.runtimeDir" to runtime.toString()))
        startApplication()
        assertEquals(HttpStatusCode.OK, readyStatus())

        val lost = runtime.resolve(TestRuntime.jars.first().fileName.toString())
        val kept = Files.readAllBytes(lost)
        Files.delete(lost)

        val ready = client.get("/api/v1/health/ready")
        assertEquals(HttpStatusCode.ServiceUnavailable, ready.status)
        assertEquals(
            """{"status":"not_ready","checks":{"startup":"ok","database":"ok","runtime":"failed","shutdown":"ok"}}""",
            ready.bodyAsText(),
        )
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/health/live").status)

        Files.write(lost, kept)
        assertEquals(HttpStatusCode.OK, readyStatus())
      }

  @Test
  fun `a run runtime directory that is gone makes ready 503`() = testApplication {
    val runtime = TestDirectories.forThisTest("run-runtime")
    TestRuntime.jars.forEach { Files.copy(it, runtime.resolve(it.fileName.toString())) }
    configureEngine(overrides = mapOf("runs.runtimeDir" to runtime.toString()))
    startApplication()

    runtime.toFile().deleteRecursively()

    assertEquals(HttpStatusCode.ServiceUnavailable, readyStatus())
  }

  /** What the server does when the process is told to stop, before the grace time begins. */
  private fun ApplicationTestBuilder.signalToStop() {
    application.monitor.raise(ApplicationStopPreparing, application.environment)
  }

  @Test
  fun `from the signal to stop ready is 503 shutting_down and live stays 200, neither closing the connection`() =
      testApplication {
        configureEngine()
        startApplication()
        assertEquals(HttpStatusCode.OK, readyStatus())

        signalToStop()

        val ready = client.get("/api/v1/health/ready")
        assertEquals(HttpStatusCode.ServiceUnavailable, ready.status)
        assertEquals(
            """{"status":"shutting_down","checks":{"startup":"ok","database":"ok","runtime":"ok","shutdown":"failed"}}""",
            ready.bodyAsText(),
        )
        val live = client.get("/api/v1/health/live")
        assertEquals(HttpStatusCode.OK, live.status)
        assertEquals("""{"status":"up"}""", live.bodyAsText())
        assertNull(ready.headers[HttpHeaders.Connection])
        assertNull(live.headers[HttpHeaders.Connection])
      }

  @Test
  fun `every other request is still refused while the Engine stops`() = testApplication {
    configureEngine()
    startApplication()

    signalToStop()

    val response = client.get("/api/v1/info")
    assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    assertTrue(response.bodyAsText().contains("shutting_down"))
    assertEquals("close", response.headers[HttpHeaders.Connection])
  }

  @Test
  fun `an Engine that is not ready still takes requests`() = testApplication {
    val runtime = TestDirectories.forThisTest("run-runtime")
    TestRuntime.jars.forEach { Files.copy(it, runtime.resolve(it.fileName.toString())) }
    configureEngine(overrides = mapOf("runs.runtimeDir" to runtime.toString()))
    startApplication()
    runtime.toFile().deleteRecursively()
    assertEquals(HttpStatusCode.ServiceUnavailable, readyStatus())

    assertEquals(HttpStatusCode.OK, client.get("/api/v1/info").status)
  }

  @Test
  fun `the probes answer GET only`() = testApplication {
    configureEngine()
    startApplication()

    for (path in listOf("/api/v1/health/live", "/api/v1/health/ready")) {
      assertNotEquals(HttpStatusCode.OK, client.post(path).status, "POST $path")
    }
  }
}
