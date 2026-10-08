package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.accessors.fake.FakeOpenAiServer
import dev.lawlan.runline.accessors.jdbc.JdbcPools
import dev.lawlan.runline.accessors.jdbc.JdbcProfiles
import dev.lawlan.runline.accessors.jdbc.PostgresProfile
import dev.lawlan.runline.accessors.openai.OpenAiBinding
import dev.lawlan.runline.accessors.openai.OpenAiCredential
import dev.lawlan.runline.accessors.openai.OpenAiSettings
import dev.lawlan.runline.accessors.openai.SettingsResult
import dev.lawlan.runline.accessors.tls.ResourceTls
import dev.lawlan.runline.accessors.tls.TestPki
import dev.lawlan.runline.accessors.tls.TlsFailure
import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.secret.KeystoreSecretStore
import dev.lawlan.runline.engine.secret.SecretCatalog
import dev.lawlan.runline.engine.secret.SecretTelemetry
import dev.lawlan.runline.engine.secret.SecretValue
import dev.lawlan.runline.engine.support.CapturedLogs
import dev.lawlan.runline.engine.support.Keystores
import dev.lawlan.runline.engine.support.migratedDatabase
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import kotlin.test.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * What the Engine records of TLS (WI-52): failures by category, labelled by resource, type and
 * category only; the days each certificate alias has left, labelled by the alias only; and a log
 * line with the resource and the category, never anything of a key.
 */
class TlsTelemetryTest {
  private val metrics = InMemoryMetricReader.create()
  private val otel =
      OpenTelemetrySdk.builder()
          .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metrics).build())
          .build()
  private val pki = TestPki()
  private val authority = pki.authority("Internal CA")
  private val server =
      FakeOpenAiServer(
          tls = pki.serverContext(pki.issue(authority, "localhost", listOf("dns:localhost")))
      )
  private val logs = CapturedLogs()

  @AfterTest
  fun close() {
    server.close()
    logs.close()
  }

  @Test
  fun `a TLS failure of a call is counted by category and logged with the resource`() {
    val settings =
        (OpenAiSettings.parse(
                Json.parseToJsonElement("""{"baseUrl":"${server.baseUrl("localhost")}"}""")
                    .jsonObject
            ) as SettingsResult.Valid)
            .settings
    val other = ResourceTls(listOf(pki.authority("Other CA").certificate), null)
    val binding =
        OpenAiBinding(
            "secure",
            settings,
            OpenAiCredential.None,
            OpenAiTelemetry(otel, OpenAiUsage()),
            other,
        )

    runCatching {
      binding.execute("openai.call", mapOf("endpoint" to "models.list", "body" to null))
    }
    binding.close()
    JdbcTelemetry(otel, JdbcPools(JdbcProfiles(listOf(PostgresProfile))))
        .tlsFailed("db", TlsFailure.CLIENT_CERT_REJECTED)

    val counted =
        metrics
            .collectAllMetrics()
            .single { it.name == "runline.resources.tls.failures" }
            .longSumData
            .points
    assertEquals(
        setOf(
            Triple("secure", "openai-compatible", "trust_failed"),
            Triple("db", "jdbc-pool", "client_cert_rejected"),
        ),
        counted
            .map {
              Triple(
                  it.attributes.get(AttributeKey.stringKey("resource")),
                  it.attributes.get(AttributeKey.stringKey("type")),
                  it.attributes.get(AttributeKey.stringKey("kind")),
              )
            }
            .toSet(),
    )
    assertTrue(counted.all { it.attributes.size() == 3 })
    val line = logs.lines.single { it.contains("trust_failed") }
    assertTrue(line.startsWith("WARN") && line.contains("secure"), line)
  }

  @Test
  fun `the days each certificate alias has left are a gauge labelled by the alias alone`() {
    val keystores = Keystores()
    val passwordFile = keystores.passwordFile()
    val file = keystores.pkcs12("gauge.p12", mapOf("api-key" to "sk-1"))
    pki.addTrusted(file, "internal-ca", authority, passwordFile)
    pki.addPrivateKey(
        file,
        "app-client",
        pki.issue(authority, "app-client", days = 10),
        passwordFile,
    )
    KeystoreSecretStore.open(file, SecretValue(Keystores.DEFAULT_PASSWORD)).use { store ->
      SecretCatalog(
          store,
          dev.lawlan.runline.engine.resource.PostgresResourceStore(
              dataSourceOf(migratedDatabase())
          ),
          SecretTelemetry(otel),
      )

      val gauge =
          metrics.collectAllMetrics().single { it.name == "runline.secrets.certificate.days_left" }
      val byAlias =
          gauge.longGaugeData.points.associate {
            it.attributes.get(AttributeKey.stringKey("alias")) to it.value
          }
      assertEquals(setOf("internal-ca", "app-client"), byAlias.keys)
      assertTrue(byAlias.getValue("app-client") in 8L..10L, "$byAlias")
      assertTrue(byAlias.getValue("internal-ca") in 363L..365L, "$byAlias")
      assertTrue(gauge.longGaugeData.points.all { it.attributes.size() == 1 })
    }
  }
}
