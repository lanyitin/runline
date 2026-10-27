package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.engine.support.Keystores
import dev.lawlan.runline.engine.support.OtlpReceiver
import dev.lawlan.runline.engine.support.ResourceApiSupport
import dev.lawlan.runline.engine.support.TestTokens
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*

/**
 * `trace-export` resources through the whole Engine (ADR-46, #51): real PostgreSQL (the real
 * migration), the real application, a real keystore and a real OTLP receiver. How one is defined,
 * that its capacity is fixed and cannot be edited, that the credential is never in an answer, and
 * what its check says.
 */
class TraceExportResourceApiTest : ResourceApiSupport() {
  private val credential = "Basic dGVzdC1hcGktY3JlZGVudGlhbA=="
  private val keystores = Keystores()
  private val passwordFile = keystores.passwordFile()
  private val receiver = OtlpReceiver()

  @AfterTest fun stop() = receiver.close()

  private fun keystore(vararg secrets: Pair<String, String>): Path =
      keystores.pkcs12("api-${System.nanoTime()}.p12", mapOf(*secrets))

  private fun ApplicationTestBuilder.engineWith(
      file: Path? = keystore("langfuse-auth" to credential),
      vararg overrides: Pair<String, String>,
  ) =
      engine(
          *(if (file == null) emptyArray()
          else
              arrayOf(
                  "secrets.keystorePath" to "$file",
                  "secrets.passwordFile" to "$passwordFile",
              )),
          *overrides,
      )

  private fun settings(endpoint: String = receiver.endpoint, serviceName: String = "transcribe") =
      """"settings":{"endpoint":"$endpoint","serviceName":"$serviceName"}"""

  /**
   * Defines a resource the way the Console does: with the capacity the Engine tells it to use
   * (`fixedCapacity` of the type catalog), which the form shows and does not let anyone edit.
   */
  private suspend fun ApplicationTestBuilder.defineTrace(
      name: String = "langfuse",
      body: String =
          """"capacity":$TRACE_EXPORT_CAPACITY,"type":"trace-export",${settings()},"secretAlias":"langfuse-auth"""",
  ) =
      client.post("/api/v1/resources") {
        bearer(TestTokens.ROOT)()
        contentType(ContentType.Application.Json)
        setBody("""{"name":"$name",$body}""")
      }

  private suspend fun ApplicationTestBuilder.check(name: String = "langfuse") =
      client.post("/api/v1/resources/$name/check") { bearer(TestTokens.ROOT)() }

  @Test
  fun `a resource is defined by its endpoint, service name and alias, with the fixed capacity`() =
      testApplication {
        engineWith()

        val response = defineTrace()

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val body = response.json()
        assertEquals("trace-export", body.text("type"))
        assertEquals(TRACE_EXPORT_CAPACITY, body["capacity"]!!.jsonPrimitive.int)
        assertEquals("langfuse-auth", body.text("secretAlias"))
        val stored = body["settings"]!!.jsonObject
        assertEquals(receiver.endpoint, stored.text("endpoint"))
        assertEquals("transcribe", stored.text("serviceName"))
      }

  @Test
  fun `the capacity given with the definition must be the fixed one, and cannot be changed later`() =
      testApplication {
        engineWith()
        val rejected =
            defineTrace(
                body =
                    """"capacity":5,"type":"trace-export",${settings()},"secretAlias":"langfuse-auth""""
            )
        assertEquals(HttpStatusCode.UnprocessableEntity, rejected.status)
        assertEquals("capacity", rejected.json().text("problem"))
        // As for every type, a definition without a capacity is not a definition.
        val missing =
            defineTrace(
                body = """"type":"trace-export",${settings()},"secretAlias":"langfuse-auth""""
            )
        assertEquals(HttpStatusCode.BadRequest, missing.status)
        defineTrace()

        val changed = change("langfuse", """{"capacity":3}""")
        val same = change("langfuse", """{"capacity":$TRACE_EXPORT_CAPACITY}""")

        assertEquals(HttpStatusCode.UnprocessableEntity, changed.status)
        assertEquals("capacity", changed.json().text("problem"))
        assertEquals(HttpStatusCode.OK, same.status)
        assertEquals(
            TRACE_EXPORT_CAPACITY,
            resource("langfuse")["capacity"]!!.jsonPrimitive.int,
        )
      }

  @Test
  fun `a resource can be disabled and enabled like any other`() = testApplication {
    engineWith()
    defineTrace()

    assertEquals(HttpStatusCode.OK, change("langfuse", """{"enabled":false}""").status)
    assertEquals(JsonPrimitive(false), resource("langfuse")["enabled"])
    assertEquals(HttpStatusCode.OK, change("langfuse", """{"enabled":true}""").status)
  }

  @Test
  fun `settings that do not make a resource are refused with their category`() = testApplication {
    engineWith()
    val cases =
        mapOf(
            """"type":"trace-export","settings":{"endpoint":"https://x.example/v1/traces"},"secretAlias":"langfuse-auth"""" to
                "invalid_settings",
            """"type":"trace-export","settings":{"endpoint":"ftp://x.example/t","serviceName":"s"},"secretAlias":"langfuse-auth"""" to
                "invalid_base_url",
            """"type":"trace-export",${settings()}""" to "invalid_secret_alias",
            """"type":"trace-export",${settings()},"secretAlias":"bad alias!"""" to
                "invalid_secret_alias",
        )

    for ((body, problem) in cases) {
      val response = defineTrace("bad", """"capacity":$TRACE_EXPORT_CAPACITY,$body""")
      assertEquals(HttpStatusCode.UnprocessableEntity, response.status, body)
      assertEquals(problem, response.json().text("problem"), body)
    }
  }

  @Test
  fun `no answer holds the credential, only the alias`() = testApplication {
    engineWith()
    defineTrace()
    check()

    val everything =
        listOf(
                defineTrace("another").bodyAsText(),
                get("/api/v1/resources", TestTokens.ROOT).bodyAsText(),
                get("/api/v1/resources/langfuse", TestTokens.ROOT).bodyAsText(),
                get("/api/v1/secrets", TestTokens.ROOT).bodyAsText(),
                check().bodyAsText(),
            )
            .joinToString("\n")
    assertFalse(everything.contains(credential))
    assertFalse(everything.contains("dGVzdC1hcGktY3JlZGVudGlhbA"))
  }

  @Test
  fun `a check says the endpoint is fine, sending no span`() = testApplication {
    engineWith()
    defineTrace()

    val response = check()

    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    assertEquals(JsonPrimitive(true), response.json()["ok"])
    assertEquals(credential, receiver.requests.single().authorization)
    assertTrue(receiver.spans.isEmpty())
  }

  @Test
  fun `a check without a keystore says the alias is missing`() = testApplication {
    engineWith(null)
    defineTrace()

    val response = check()

    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    assertEquals(JsonPrimitive(false), response.json()["ok"])
    assertEquals("alias_missing", response.json().text("failure"))
  }

  private val tracing =
      """
      context.getAccessors().traceExport("tracing").submit(new SpanRecord("through-the-engine", java.time.Instant.now(), java.time.Instant.now(), SpanStatus.OK, null, new java.util.HashMap<String, Object>(), "s1"));
      """
          .trimIndent()

  @Test
  fun `a pipeline that declares tracing is accepted without a resource, with no warning, and runs`() =
      testApplication {
        engineWith(
            overrides =
                arrayOf("analysis.allowlist" to "java.lang,java.util,java.io,java.time,kotlin")
        )

        val upload = upload("traced", types = mapOf("tracing" to "trace-export"), body = tracing)
        assertEquals(HttpStatusCode.Created, upload.status, upload.bodyAsText())
        assertEquals(emptyList(), upload.json().array("pipelines").single().array("warnings"))
        val hash = upload.json().text("contentHash")

        awaitState(start(hash, "traced"), "SUCCEEDED")
        assertTrue(receiver.requests.isEmpty())
      }

  @Test
  fun `a run exports through the Engine as it is configured, with the numbers of its deployment`() =
      testApplication {
        engineWith(
            overrides =
                arrayOf(
                    "analysis.allowlist" to "java.lang,java.util,java.io,java.time,kotlin",
                    "traceExport.scheduleDelayMillis" to "50",
                )
        )
        defineTrace("tracing")
        val hash = uploaded("traced", types = mapOf("tracing" to "trace-export"), body = tracing)

        awaitState(start(hash, "traced"), "SUCCEEDED")

        val deadline = System.nanoTime() + 15_000_000_000
        while (receiver.spans.isEmpty() && System.nanoTime() < deadline) {
          kotlinx.coroutines.delay(20)
        }
        assertEquals(listOf("through-the-engine"), receiver.spans.map { it.name })
        assertEquals(credential, receiver.requests.single().authorization)
      }

  @Test
  fun `the Engine lists the type with its fixed capacity, from the one description it validates with`() =
      testApplication {
        engineWith()

        val types = get("/api/v1/resource-types", TestTokens.ROOT).json().array("types")

        val entry = types.single { it.text("type") == "trace-export" }
        assertEquals(TRACE_EXPORT_CAPACITY, entry["fixedCapacity"]!!.jsonPrimitive.int)
        assertEquals("trace-export", types.last().text("type"))
      }
}
