package dev.lawlan.runline.engine

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import dev.lawlan.runline.engine.config.DatabaseConfig
import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.support.PipelineJars
import dev.lawlan.runline.engine.support.SnapshotListAppender
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
import kotlin.test.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

/**
 * An unexpected failure is made real by taking a table away from a real PostgreSQL, so the failure
 * is the database's own error, which names the table, the driver and the SQL state: exactly what
 * must not reach the caller.
 */
class UnexpectedErrorTest {
  private val dir = Files.createTempDirectory("unexpected-error")
  private val database: DatabaseConfig = migratedDatabase()
  private val appender = SnapshotListAppender().also { it.start() }
  private val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger

  @BeforeTest
  fun capture() {
    root.addAppender(appender)
  }

  @AfterTest
  fun release() {
    root.detachAppender(appender)
  }

  private fun ApplicationTestBuilder.engine() =
      configureEngine(
          database,
          mapOf("analysis.allowlist" to "java.lang,java.util,java.io,kotlin"),
      )

  private fun sql(statement: String) =
      dataSourceOf(database).connection.use { c ->
        c.createStatement().use { it.execute(statement) }
      }

  private fun bearer(token: String): HttpRequestBuilder.() -> Unit = {
    header(HttpHeaders.Authorization, "Bearer $token")
  }

  private val internals =
      listOf(
          "pipeline_artifact",
          "relation",
          "PSQL",
          "SQLException",
          "Exception",
          "org.postgresql",
          "at dev.lawlan",
          "jdbc:",
          "postgres",
      )

  private fun errorEvents() = appender.snapshot().filter { it.level == Level.ERROR }

  @Test
  fun `an unexpected failure answers with a generic message and an identifier, nothing internal`() =
      testApplication {
        engine()
        sql("ALTER TABLE pipeline_artifact RENAME TO pipeline_artifact_gone")

        val response = client.get("/api/v1/definitions", bearer(TestTokens.ROOT))

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
        val text = response.bodyAsText()
        val body = Json.parseToJsonElement(text).jsonObject
        assertEquals("internal_error", body["error"]!!.jsonPrimitive.content)
        val errorId = body["errorId"]!!.jsonPrimitive.content
        assertTrue(errorId.isNotBlank())
        assertTrue(body["message"]!!.jsonPrimitive.content.contains(errorId), "tells the id: $text")
        for (secret in internals + database.password + database.user) {
          assertFalse(text.contains(secret, ignoreCase = true), "response leaks '$secret': $text")
        }
        assertFalse(response.headers.toString().contains("pipeline_artifact"))
      }

  @Test
  fun `the identifier finds the full cause in the log`() = testApplication {
    engine()
    sql("ALTER TABLE pipeline_artifact RENAME TO pipeline_artifact_gone")

    val response = client.get("/api/v1/definitions", bearer(TestTokens.ROOT))

    val errorId =
        Json.parseToJsonElement(response.bodyAsText()).jsonObject["errorId"]!!.jsonPrimitive.content
    val logged = errorEvents().filter { it.formattedMessage.contains(errorId) }
    assertEquals(1, logged.size, "exactly one log event carries $errorId")
    val cause = logged.single().throwableProxy
    assertNotNull(cause, "the exception itself is logged")
    assertTrue(
        generateSequence(cause) { it.cause }
            .any { it.message.orEmpty().contains("pipeline_artifact") },
        "the database's own reason is in the log",
    )
  }

  @Test
  fun `each failure gets its own identifier`() = testApplication {
    engine()
    sql("ALTER TABLE pipeline_artifact RENAME TO pipeline_artifact_gone")

    fun id(response: String) =
        Json.parseToJsonElement(response).jsonObject["errorId"]!!.jsonPrimitive.content

    val first = id(client.get("/api/v1/definitions", bearer(TestTokens.ROOT)).bodyAsText())
    val second = id(client.get("/api/v1/definitions", bearer(TestTokens.ROOT)).bodyAsText())

    assertNotEquals(first, second)
  }

  @Test
  fun `existing error answers are unchanged`() = testApplication {
    engine()

    assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/definitions").status)
    assertEquals(
        HttpStatusCode.Forbidden,
        client.delete("/api/v1/artifacts/${"a".repeat(64)}", bearer(TestTokens.ALICE)).status,
    )
    assertEquals(
        HttpStatusCode.NotFound,
        client.get("/api/v1/runs/${java.util.UUID.randomUUID()}", bearer(TestTokens.ROOT)).status,
    )
  }

  @Test
  fun `a failure inside a log stream closes it without the internal reason`() = testApplication {
    engine()
    val jar =
        PipelineJars.build(
            dir,
            "j.jar",
            mapOf("demo.Q" to PipelineJars.pipeline("demo.Q", "quiet")),
        )
    val hash =
        client
            .post("/api/v1/artifacts") {
              bearer(TestTokens.ALICE)()
              contentType(ContentType.Application.OctetStream)
              setBody(Files.readAllBytes(jar))
            }
            .bodyAsText()
            .let { Json.parseToJsonElement(it).jsonObject["contentHash"]!!.jsonPrimitive.content }
    val created =
        client.post("/api/v1/runs") {
          bearer(TestTokens.ALICE)()
          contentType(ContentType.Application.Json)
          setBody("""{"contentHash":"$hash","pipeline":"quiet"}""")
        }
    assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
    val id =
        Json.parseToJsonElement(created.bodyAsText()).jsonObject["runId"]!!.jsonPrimitive.content
    withTimeout(30_000) {
      while (
          Json.parseToJsonElement(
                  client.get("/api/v1/runs/$id", bearer(TestTokens.ROOT)).bodyAsText()
              )
              .jsonObject["state"]!!
              .jsonPrimitive
              .content != "SUCCEEDED"
      ) delay(25)
    }
    sql("ALTER TABLE run_log_entry RENAME TO run_log_entry_gone")
    val wsClient = createClient { install(WebSockets) }

    var reason: CloseReason? = null
    wsClient.webSocket("/api/v1/runs/$id/log/stream", request = bearer(TestTokens.ALICE)) {
      withTimeout(30_000) { reason = closeReason.await() }
    }

    val closed = assertNotNull(reason)
    assertEquals(CloseReason.Codes.INTERNAL_ERROR.code, closed.code)
    for (secret in internals + "run_log_entry") {
      assertFalse(
          closed.message.contains(secret, ignoreCase = true),
          "close reason leaks: ${closed.message}",
      )
    }
    val errorId = Regex("[0-9a-f]{8}-[0-9a-f-]{27}").find(closed.message)?.value
    assertNotNull(errorId, "the close reason carries an identifier: ${closed.message}")
    assertTrue(errorEvents().any { it.formattedMessage.contains(errorId) }, "the id is in the log")
  }
}
