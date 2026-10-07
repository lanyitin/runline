package dev.lawlan.runline.engine.secret

import dev.lawlan.runline.engine.auth.ApiIdentity
import dev.lawlan.runline.engine.auth.Role
import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.resource.PostgresResourceStore
import dev.lawlan.runline.engine.resource.ResourceType
import dev.lawlan.runline.engine.resource.SharedResource
import dev.lawlan.runline.engine.support.CapturedLogs
import dev.lawlan.runline.engine.support.Keystores
import dev.lawlan.runline.engine.support.ResourceApiSupport
import dev.lawlan.runline.engine.support.TestTokens
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import kotlin.test.*

/**
 * A keystore with markers in it, the Engine used the ways an administrator uses it, and everything
 * the Engine says searched for the markers (WI-41; the end-to-end case, a service that echoes a key
 * back, is WI-46 and WI-51). The marker of a secret and the marker of the keystore password must be
 * in no response, no stored data, no log line and no metric.
 */
class SecretNonLeakTest : ResourceApiSupport() {
  private val keystores = Keystores()
  private val secretMarker = "leak-marker-secret-8F3A91"
  private val passwordMarker = "leak-marker-storepass-7C2B04"
  private val markers = listOf(secretMarker, passwordMarker)
  private val passwordFile = keystores.passwordFile(passwordMarker)

  private fun keystore(password: String = passwordMarker): Path =
      keystores.pkcs12(
          "marked.p12",
          mapOf("api-key" to secretMarker, "bad" to "café"),
          password,
      )

  private fun replace(file: Path, with: Path) =
      Files.move(with, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)

  /** Everything every table of the database holds, as text. */
  private fun databaseText(): String =
      dataSourceOf(database).connection.use { c ->
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
              generateSequence { if (rs.next()) rs.getString(1) else null }.joinToString("\n")
            }
          }
        }
      }

  @Test
  fun `no marker is in a response, in stored data, in the Engine log or in the console output`() {
    val said = StringBuilder()
    val original = System.out
    val console = ByteArrayOutputStream()
    System.setOut(PrintStream(console, true, Charsets.UTF_8))
    val logs = CapturedLogs()
    try {
      testApplication {
        val file = keystore()
        val now = Instant.now()
        PostgresResourceStore(dataSourceOf(database))
            .insert(
                SharedResource(
                    "warehouse",
                    1,
                    true,
                    "root",
                    now,
                    "root",
                    now,
                    ResourceType.COUNTER,
                    secretAlias = "api-key",
                )
            )
        engine("secrets.keystorePath" to "$file", "secrets.passwordFile" to "$passwordFile")
        startApplication()

        suspend fun say(response: HttpResponse): HttpResponse {
          said
              .append(response.status)
              .append(response.headers.toString())
              .append(response.bodyAsText())
          return response
        }

        say(client.get("/api/v1/secrets") { bearer(TestTokens.ROOT)() })
        say(client.post("/api/v1/secrets/reload") { bearer(TestTokens.ROOT)() })
        say(client.get("/api/v1/resources") { bearer(TestTokens.ROOT)() })
        say(client.get("/api/v1/secrets") { bearer(TestTokens.ALICE)() })
        say(client.get("/api/v1/secrets/api-key") { bearer(TestTokens.ROOT)() })
        // Reloads that fail, each in its own way, with the keystore password in the air.
        replace(
            file,
            keystores.pkcs12(
                "other-password.p12",
                mapOf("x" to secretMarker),
                "another-password-1",
            ),
        )
        assertEquals(
            "wrong_password",
            say(client.post("/api/v1/secrets/reload") { bearer(TestTokens.ROOT)() }).let {
              Regex("\"problem\":\"(\\w+)\"").find(it.bodyAsText())!!.groupValues[1]
            },
        )
        replace(file, keystores.otherFormat("legacy.jks", "JKS", passwordMarker))
        say(client.post("/api/v1/secrets/reload") { bearer(TestTokens.ROOT)() })
        Files.writeString(file, "$secretMarker $passwordMarker\n".repeat(20))
        say(client.post("/api/v1/secrets/reload") { bearer(TestTokens.ROOT)() })
        // An unexpected failure, which writes its whole cause to the log.
        dataSourceOf(database).connection.use { c ->
          c.createStatement().use {
            it.execute("ALTER TABLE shared_resource RENAME TO shared_resource_gone")
          }
        }
        val failed = say(client.get("/api/v1/secrets") { bearer(TestTokens.ROOT)() })
        assertEquals(HttpStatusCode.InternalServerError, failed.status)
      }
    } finally {
      System.setOut(original)
      logs.close()
    }

    val written = console.toString(Charsets.UTF_8)
    assertTrue(written.isNotBlank(), "the console output was captured")
    for (marker in markers) {
      assertFalse(said.contains(marker), "$marker is in a response")
      assertFalse(logs.lines.any { it.contains(marker) }, "$marker is in the log")
      assertFalse(written.contains(marker), "$marker is on the console")
    }
    // The tables are read after the table of resources was put back.
    dataSourceOf(database).connection.use { c ->
      c.createStatement().use {
        it.execute("ALTER TABLE shared_resource_gone RENAME TO shared_resource")
      }
    }
    val stored = databaseText()
    for (marker in markers) assertFalse(stored.contains(marker), "$marker is stored")
  }

  @Test
  fun `no marker is in a metric or in its labels`() {
    val reader = InMemoryMetricReader.create()
    val otel =
        OpenTelemetrySdk.builder()
            .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(reader).build())
            .build()
    val file = keystore()
    val catalog =
        SecretCatalog(
            KeystoreSecretStore.open(file, SecretValue(passwordMarker)),
            PostgresResourceStore(dataSourceOf(database)),
            SecretTelemetry(otel),
        )
    catalog.reload(ApiIdentity("root", Role.ADMIN))
    Files.writeString(file, "$secretMarker\n".repeat(20))
    catalog.reload(ApiIdentity("root", Role.ADMIN))

    val metrics = reader.collectAllMetrics().joinToString("\n") { "$it" }

    assertTrue(metrics.contains("runline.secrets.reloads"), metrics)
    for (marker in markers) assertFalse(metrics.contains(marker), "$marker is in a metric")
  }
}
