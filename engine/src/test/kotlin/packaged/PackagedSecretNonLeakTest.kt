package dev.lawlan.runline.engine.packaged

import dev.lawlan.runline.accessors.fake.FakeOpenAiServer
import dev.lawlan.runline.accessors.jdbc.RealPostgres
import dev.lawlan.runline.accessors.support.TestDirectories
import dev.lawlan.runline.engine.support.Keystores
import dev.lawlan.runline.engine.support.OtelCollector
import dev.lawlan.runline.engine.support.PackagedEngine
import dev.lawlan.runline.engine.support.PackagedEngine.Companion.ALICE
import dev.lawlan.runline.engine.support.PostgresTestContainer
import dev.lawlan.runline.engine.support.awaitCondition
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*
import kotlinx.serialization.json.*

/**
 * The secret non-leak rules verified as a whole on the packaged Engine (WI-51, ADR-019 decision 6):
 * a real keystore made with keytool whose database password, API keys and keystore password are
 * markers that occur nowhere else, a real PostgreSQL behind a `jdbc-pool` resource and the Fake
 * OpenAI compatible service behind an `openai-compatible` one, real compiled pipelines, and every
 * failure the rules are about made to happen: a connection that fails, a password and a key that
 * are refused, a service that echoes the key back in an error, in headers, in a body and in a
 * stream, a server error, unexpected exceptions in a pipeline and in the Engine. Then every surface
 * is searched for every marker: every answer of the API, the Engine's database and its dump, the
 * Engine's output, the runs' logs, and what the Engine sent to a real OpenTelemetry Collector
 * (traces, metrics, logs).
 */
class PackagedSecretNonLeakTest {
  private val work: Path = TestDirectories.forThisTest("packaged-secrets")
  private val engine = PackagedEngine(work)
  private val keystores = Keystores(Files.createDirectories(work.resolve("keystore")))
  private val closeable = mutableListOf<AutoCloseable>()

  private fun marker(kind: String) = "$kind-marker-${UUID.randomUUID().toString().take(12)}"

  private val apiKey = marker("sk")
  private val wrongKey = marker("sk-wrong")
  private val dbPassword = marker("dbpw")
  private val wrongDbPassword = marker("dbpw-wrong")
  private val storePassword = marker("storepass")
  private val markers = listOf(apiKey, wrongKey, dbPassword, wrongDbPassword, storePassword)

  @AfterTest
  fun close() {
    engine.close()
    closeable.reversed().forEach { runCatching { it.close() } }
  }

  /** A service that knows the key and echoes it back in the ways the model of a request asks. */
  private fun echoingService(): FakeOpenAiServer {
    val server = FakeOpenAiServer(requiredKey = apiKey).also { closeable += it }
    server.script = { request, response ->
      val sent = request.header("authorization").orEmpty()
      val scenario = Regex("\"model\":\"([a-z-]+)\"").find(request.body)?.groupValues?.get(1)
      when {
        sent != "Bearer $apiKey" -> {
          // A service that names the key it refused, as some do.
          response.json(
              401,
              """{"error":{"message":"Incorrect API key provided: $sent","code":"invalid_api_key"}}""",
              mapOf("X-Echo" to sent),
          )
          true
        }
        scenario == "echo-error" -> {
          response.json(
              401,
              """{"error":{"message":"Incorrect API key provided: $apiKey (you sent '$sent')"}}""",
              mapOf(
                  "X-Echo-Authorization" to sent,
                  "WWW-Authenticate" to "Bearer realm=\"fake\", error=\"$apiKey\"",
                  "Set-Cookie" to "session=$apiKey",
              ),
          )
          true
        }
        scenario == "echo-headers" -> {
          response.json(
              200,
              "{}",
              mapOf(
                  "X-Debug" to "you sent $sent",
                  "X-Api-Key" to apiKey,
                  "X-Request-Id" to "req-1",
              ),
          )
          true
        }
        scenario == "echo-body" -> {
          response.json(200, """{"echo":"$sent"}""")
          true
        }
        scenario == "server-error" -> {
          response.json(500, """{"error":{"message":"internal failure with $sent"}}""")
          true
        }
        scenario == "echo-stream" -> {
          response.beginChunked(
              200,
              mapOf("Content-Type" to "text/event-stream", "X-Echo-Authorization" to sent),
          )
          response.event("""{"echo":"$sent"}""")
          response.event("[DONE]")
          response.endChunked()
          true
        }
        else -> false
      }
    }
    return server
  }

  private fun keystore(extra: Map<String, String> = emptyMap()): Path =
      keystores.pkcs12(
          "engine.p12",
          mapOf(
              "api-key" to apiKey,
              "wrong-key" to wrongKey,
              "db-pw" to dbPassword,
              "db-wrong-pw" to wrongDbPassword,
          ) + extra,
          storePassword,
      )

  private fun define(name: String, type: String, alias: String?, settings: String) =
      engine.expect(
          201,
          "POST",
          "/api/v1/resources",
          """{"name":"$name","capacity":1,"type":"$type",""" +
              (alias?.let { """"secretAlias":"$it",""" } ?: "") +
              """"settings":$settings}""",
      )

  private fun jdbcSettings(database: String, role: String, port: Int = RealPostgres.port) =
      """{"kind":"postgresql","host":"${RealPostgres.host}","port":$port,""" +
          """"database":"$database","username":"$role"}"""

  private fun check(name: String): JsonObject =
      engine.expect(200, "POST", "/api/v1/resources/$name/check")

  /** Everything the pipeline `llm-scenarios` does: each echo of the service, written down. */
  private val scenarios =
      """
      OpenAiAccessor llm = context.getAccessors().openAiCompatible("llm");
      StringBuilder seen = new StringBuilder();
      for (String scenario : new String[] {"echo-error", "echo-headers", "echo-body", "server-error", "echo-stream"}) {
        try {
          OpenAiRequest request = new OpenAiRequest("chat.completions", "{\"model\":\"" + scenario + "\"}");
          if (scenario.equals("echo-stream")) {
            try (OpenAiStream s = llm.stream(request)) {
              seen.append(scenario).append(" headers ").append(s.getHeaders()).append('\n');
              String event;
              while ((event = s.next()) != null) {
                seen.append(scenario).append(" event ").append(event).append('\n');
                System.out.println("event " + event);
              }
            }
          } else {
            OpenAiResponse r = llm.call(request);
            seen.append(scenario).append(" headers ").append(r.getHeaders()).append('\n');
            seen.append(scenario).append(" body ").append(r.getBody()).append('\n');
            System.out.println("body " + r.getBody());
            System.err.println("headers " + r.getHeaders());
          }
        } catch (ResourceAccessException e) {
          seen.append(scenario).append(" failed ").append(e.getFailure()).append('|')
              .append(e.getStatus()).append('|').append(e.getMessage()).append('\n');
          System.out.println("failed " + e.getMessage());
        }
      }
      context.getFiles().writeText(FileScope.PIPELINE_SHARED, "seen", seen.toString());
      """
          .trimIndent()

  private fun Path.text(): String = Files.readString(this)

  /** What shows that the collector received the metrics and spans of the resources. */
  private val expectedInCollector =
      listOf(
          "runline.resources.openai.requests",
          "runline.resource.openai.call",
          "runline.resources.jdbc.statement.duration",
      )

  @Test
  fun `no marker of a secret or of the keystore password is on any surface of the packaged Engine`() {
    val collector = OtelCollector().also { closeable += it }
    val service = echoingService()
    val target = RealPostgres.newDatabase().also { closeable += it }
    val role = "app_" + UUID.randomUUID().toString().replace("-", "").take(10)
    target.createRole(role, dbPassword, "GRANT ALL ON SCHEMA public TO $role")
    val closedPort = ServerSocket(0).use { it.localPort }
    val file = keystore()
    val original = Files.readAllBytes(file)
    engine.migrate()
    engine.start(
        mapOf(
            "RUNLINE_KEYSTORE_PATH" to file.toString(),
            "RUNLINE_KEYSTORE_PASSWORD_FILE" to
                keystores.passwordFile(storePassword, "engine.pw").toString(),
            "OTEL_TRACES_EXPORTER" to "otlp",
            "OTEL_METRICS_EXPORTER" to "otlp",
            "OTEL_LOGS_EXPORTER" to "otlp",
            "OTEL_EXPORTER_OTLP_PROTOCOL" to "http/protobuf",
            "OTEL_EXPORTER_OTLP_ENDPOINT" to collector.endpoint,
            "OTEL_METRIC_EXPORT_INTERVAL" to "1000",
            "OTEL_BSP_SCHEDULE_DELAY" to "100",
            "OTEL_BLRP_SCHEDULE_DELAY" to "100",
        )
    )
    val openAi =
        """{"baseUrl":"${service.baseUrl}","endpoints":["chat.completions","models.list"]}"""
    define("llm", "openai-compatible", "api-key", openAi)
    define("llm-wrong", "openai-compatible", "wrong-key", openAi)
    define("db", "jdbc-pool", "db-pw", jdbcSettings(target.name, role))
    define("db-wrong", "jdbc-pool", "db-wrong-pw", jdbcSettings(target.name, role))
    define("db-down", "jdbc-pool", "db-pw", jdbcSettings(target.name, role, closedPort))

    // Checks: passing ones and failing ones of each kind.
    assertEquals(JsonPrimitive(true), check("llm")["ok"])
    assertEquals("rejected", check("llm-wrong")["failure"]!!.jsonPrimitive.content)
    assertEquals(JsonPrimitive(true), check("db")["ok"])
    assertNotNull(check("db-wrong")["failure"])
    assertNotNull(check("db-down")["failure"])

    // The service echoes the key in every way it can.
    val scenariosRun =
        engine.runToEnd(
            "llm-scenarios",
            PackagedEngine.typed("llm" to "openai-compatible"),
            scenarios,
        )
    assertEquals("SUCCEEDED", scenariosRun["state"]!!.jsonPrimitive.content, "$scenariosRun")
    // A pipeline that fails with what the service echoed, and one that fails unexpectedly.
    val escapes =
        engine.runToEnd(
            "llm-escapes",
            PackagedEngine.typed("llm" to "openai-compatible"),
            """
            OpenAiResponse r = context.getAccessors().openAiCompatible("llm")
                .call(new OpenAiRequest("chat.completions", "{\"model\":\"echo-body\"}"));
            throw new IllegalStateException("the service said " + r.getBody());
            """
                .trimIndent(),
        )
    assertEquals("FAILED", escapes["state"]!!.jsonPrimitive.content)
    val unexpected =
        engine.runToEnd(
            "llm-unexpected",
            PackagedEngine.typed("llm" to "openai-compatible", "llm-wrong" to "openai-compatible"),
            """
            try {
              context.getAccessors().openAiCompatible("llm-wrong")
                  .call(new OpenAiRequest("chat.completions", "{\"model\":\"plain\"}"));
            } catch (ResourceAccessException e) {
              System.out.println("refused " + e.getFailure() + " " + e.getStatus() + " " + e.getMessage());
            }
            OpenAiResponse r = context.getAccessors().openAiCompatible("llm")
                .call(new OpenAiRequest("chat.completions", "{\"model\":\"echo-headers\"}"));
            Object nothing = r.getHeaders().get("x-api-key");
            System.out.println(nothing.toString());
            """
                .trimIndent(),
        )
    assertEquals("FAILED", unexpected["state"]!!.jsonPrimitive.content)
    // The database: a statement that works, one that fails with a marker in its parameter, a
    // password that is refused and a database that cannot be reached.
    val database =
        engine.runToEnd(
            "db-uses",
            PackagedEngine.typed(
                "db" to "jdbc-pool",
                "db-wrong" to "jdbc-pool",
                "db-down" to "jdbc-pool",
            ),
            """
            JdbcAccessor db = context.getAccessors().jdbcPool("db");
            db.update("CREATE TABLE IF NOT EXISTS note (text varchar(200))");
            db.update("INSERT INTO note VALUES (?)", java.util.List.of("written"));
            System.out.println("rows " + db.query("SELECT text FROM note").getRows());
            for (String name : new String[] {"db", "db-wrong", "db-down"}) {
              try {
                context.getAccessors().jdbcPool(name).query("SELECT ?::int", java.util.List.of("not-a-number"));
              } catch (ResourceAccessException e) {
                System.out.println(name + " " + e.getFailure() + " " + e.getSqlState() + " " + e.getMessage());
              }
            }
            """
                .trimIndent(),
        )
    assertEquals("SUCCEEDED", database["state"]!!.jsonPrimitive.content, "$database")
    val databaseLog = engine.logLines(database["runId"]!!.jsonPrimitive.content)
    assertTrue("rows [[written]]" in databaseLog, "$databaseLog")
    assertTrue(databaseLog.any { it.startsWith("db SQL_ERROR 22P02") }, "$databaseLog")

    // Reloads: one that works, one of a file that is no keystore and is full of markers, and the
    // file put back.
    engine.expect(200, "POST", "/api/v1/secrets/reload")
    Files.writeString(file, markers.joinToString(" ").repeat(20))
    assertEquals(
        "secret_store_unreadable",
        engine.json(engine.call("POST", "/api/v1/secrets/reload"))["error"]!!.jsonPrimitive.content,
    )
    Files.write(file, original)
    engine.expect(200, "POST", "/api/v1/secrets/reload")

    // An unexpected failure of the Engine itself: a 500 whose cause goes to the Engine's log.
    PostgresTestContainer.connect(engine.database).use { c ->
      c.createStatement().use {
        it.execute("ALTER TABLE shared_resource RENAME TO shared_resource_gone")
      }
    }
    assertEquals(500, engine.call("POST", "/api/v1/resources/llm/check").statusCode())
    PostgresTestContainer.connect(engine.database).use { c ->
      c.createStatement().use {
        it.execute("ALTER TABLE shared_resource_gone RENAME TO shared_resource")
      }
    }

    // Everything the API can be asked about what happened.
    val runs = engine.json(engine.call("GET", "/api/v1/runs"))["runs"]!!.jsonArray
    for (run in runs) {
      val id = run.jsonObject["runId"]!!.jsonPrimitive.content
      engine.call("GET", "/api/v1/runs/$id")
      engine.call("GET", "/api/v1/runs/$id/log")
      engine.call("GET", "/api/v1/runs/$id/log", ALICE)
    }
    for (path in
        listOf(
            "/api/v1/resources",
            "/api/v1/resources/llm",
            "/api/v1/resources/db",
            "/api/v1/resources/db-wrong",
            "/api/v1/secrets",
            "/api/v1/definitions",
            "/api/v1/resource-types",
            "/api/v1/system",
            "/api/v1/triggers",
            "/api/v1/allowlist",
        )) {
      assertEquals(200, engine.call("GET", path).statusCode(), path)
    }
    engine.call("GET", "/api/v1/definitions", ALICE)
    for (definition in
        engine.json(engine.call("GET", "/api/v1/definitions"))["definitions"]!!.jsonArray) {
      engine.call(
          "GET",
          "/api/v1/artifacts/${definition.jsonObject["contentHash"]!!.jsonPrimitive.content}",
      )
    }

    // What the pipelines saw: the key never in a header or an error, in a body as it was sent back
    // (the accepted limit: the Engine does not touch a body), masked in the events of a stream.
    val seen = engine.shared("llm-scenarios", "seen").text()
    val lines = seen.lines()
    assertTrue(lines.any { it.startsWith("echo-error failed DENIED|401|") }, seen)
    assertTrue(lines.any { it.startsWith("server-error failed SERVER_ERROR|500|") }, seen)
    assertTrue(
        lines.single { it.startsWith("echo-headers headers") }.contains("you sent Bearer ***"),
        seen,
    )
    assertTrue(lines.single { it.startsWith("echo-body body") }.contains(apiKey), seen)
    assertTrue(lines.single { it.startsWith("echo-stream event") }.contains("Bearer ***"), seen)
    for (line in lines.filterNot { it.startsWith("echo-body body") }) {
      assertFalse(line.contains(apiKey), line)
    }
    assertTrue(service.requests.any { it.header("authorization") == "Bearer $apiKey" })
    assertTrue(service.requests.any { it.header("authorization") == "Bearer $wrongKey" })
    val escaped = escapes["failure"]!!.jsonObject["message"]!!.jsonPrimitive.content
    assertTrue(escaped.contains("Bearer ***"), escaped)

    // The Engine stops, so that what it has not exported yet goes out.
    engine.stop()
    awaitCondition(
        "the collector to have the metrics and spans of the resources",
        diagnostics = { expectedInCollector.associateWith { it in collector.logs }.toString() },
    ) {
      expectedInCollector.all { it in collector.logs }
    }

    val surfaces =
        mapOf(
            "the answers of the API" to engine.exchanges.joinToString("\n"),
            "the Engine's output" to engine.engineOutput(),
            "the Engine's database" to databaseText(),
            "the dump of the Engine's database" to PostgresTestContainer.dump(engine.database),
            "what the collector received" to collector.logs,
        )
    for ((surface, text) in surfaces) {
      assertTrue(text.isNotBlank(), "$surface is empty")
      for (marker in markers) assertFalse(text.contains(marker), "$marker is in $surface")
    }
    // The masking was at work where a pipeline wrote the key out.
    assertTrue(surfaces.getValue("the answers of the API").contains("Bearer ***"))
    assertTrue(surfaces.getValue("the Engine's output").contains("***"))
  }

  /** Every row of every table of the Engine's database, as text. */
  private fun databaseText(): String =
      PostgresTestContainer.connect(engine.database).use { c ->
        val tables =
            c.createStatement().use { s ->
              s.executeQuery(
                      "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'"
                  )
                  .use { rs ->
                    generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
                  }
            }
        tables.joinToString("\n") { table ->
          c.createStatement().use { s ->
            s.executeQuery("""SELECT t::text FROM "$table" t""").use { rs ->
              table +
                  ": " +
                  generateSequence { if (rs.next()) rs.getString(1) else null }.joinToString("\n")
            }
          }
        }
      }
}
