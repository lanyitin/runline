package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.engine.config.TraceExportTuning
import dev.lawlan.runline.engine.secret.NoSecretStore
import dev.lawlan.runline.engine.traceexport.TraceExportTelemetry
import dev.lawlan.runline.engine.traceexport.TraceExporters
import io.opentelemetry.api.OpenTelemetry
import java.time.Duration
import kotlin.test.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * What the Engine accepts as the definition of a `trace-export` resource (ADR-46 decision 1): an
 * OTLP endpoint, a service name and the alias of the whole credential in the keystore.
 */
class TraceExportSettingsTest {
  private val exporters =
      TraceExporters(NoSecretStore, TraceExportTuning(), TraceExportTelemetry(OpenTelemetry.noop()))
  private val behavior = TraceExportBehavior(NoSecretStore, Duration.ofSeconds(5), exporters)

  @AfterTest fun close() = exporters.close()

  private fun settings(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

  private val valid =
      """{"endpoint":"https://cloud.example/api/public/otel/v1/traces","serviceName":"transcribe"}"""

  @Test
  fun `an endpoint, a service name and an alias make a resource`() {
    assertNull(behavior.problemWith(settings(valid), "langfuse-auth"))
  }

  @Test
  fun `the alias of the credential is required`() {
    assertEquals(InvalidResource.INVALID_SECRET_ALIAS, behavior.problemWith(settings(valid), null))
  }

  @Test
  fun `an alias that is not written like one is refused`() {
    assertEquals(
        InvalidResource.INVALID_SECRET_ALIAS,
        behavior.problemWith(settings(valid), "not valid!"),
    )
  }

  @Test
  fun `settings without a service name or an endpoint, or with any other member, are refused`() {
    listOf(
            """{"endpoint":"https://x.example/v1/traces"}""",
            """{"serviceName":"transcribe"}""",
            """{"endpoint":"https://x.example/v1/traces","serviceName":"s","apiKey":"k"}""",
            """{"endpoint":"https://x.example/v1/traces","serviceName":""}""",
            """{"endpoint":"https://x.example/v1/traces","serviceName":"a${"b".repeat(200)}"}""",
            """{"endpoint":5,"serviceName":"s"}""",
        )
        .forEach {
          assertEquals(
              InvalidResource.INVALID_SETTINGS,
              behavior.problemWith(settings(it), "k"),
              it,
          )
        }
    assertEquals(InvalidResource.INVALID_SETTINGS, behavior.problemWith(null, "k"))
  }

  @Test
  fun `an endpoint that is not an http or https address of its own is refused`() {
    listOf(
            "ftp://x.example/v1/traces",
            "https://user:pw@x.example/v1/traces",
            "https://x.example/v1/traces?token=1",
            "https://x.example/v1/traces#frag",
            "not a url",
            "/relative/path",
            "https:///no-host",
        )
        .forEach {
          assertEquals(
              InvalidResource.INVALID_BASE_URL,
              behavior.problemWith(
                  settings("""{"endpoint":"$it","serviceName":"s"}"""),
                  "k",
              ),
              it,
          )
        }
  }

  @Test
  fun `the settings are stored as they were given`() {
    val given = settings(valid)

    assertEquals(given, behavior.normalized(given))
  }
}
