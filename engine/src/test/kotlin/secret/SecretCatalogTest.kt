package dev.lawlan.runline.engine.secret

import dev.lawlan.runline.engine.auth.ApiIdentity
import dev.lawlan.runline.engine.auth.Role
import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.resource.PostgresResourceStore
import dev.lawlan.runline.engine.resource.ResourceType
import dev.lawlan.runline.engine.resource.SharedResource
import dev.lawlan.runline.engine.support.CapturedLogs
import dev.lawlan.runline.engine.support.Keystores
import dev.lawlan.runline.engine.support.migratedDatabase
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import kotlin.test.*

/** The secrets an administrator sees and the reload, with a real keystore and a real database. */
class SecretCatalogTest {
  private val keystores = Keystores()
  private val passwordFile = keystores.passwordFile()
  private val resources = PostgresResourceStore(dataSourceOf(migratedDatabase()))
  private val reader = InMemoryMetricReader.create()
  private val otel =
      OpenTelemetrySdk.builder()
          .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(reader).build())
          .build()
  private val root = ApiIdentity("root", Role.ADMIN)

  private fun catalog(file: Path?): SecretCatalog =
      SecretCatalog(
          file?.let { KeystoreSecretStore.open(it, SecretValue(Keystores.DEFAULT_PASSWORD)) }
              ?: NoSecretStore,
          resources,
          SecretTelemetry(otel),
      )

  /** A resource that names [alias]; no type accepts one through the API yet, so it is stored. */
  private fun resourceUsing(name: String, alias: String) {
    val now = Instant.now()
    resources.insert(
        SharedResource(
            name,
            1,
            true,
            "root",
            now,
            "root",
            now,
            ResourceType.COUNTER,
            secretAlias = alias,
        )
    )
  }

  private fun replaceWith(file: Path, change: (Path) -> Unit) {
    val copy = file.resolveSibling(file.fileName.toString() + ".new")
    Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING)
    change(copy)
    Files.move(copy, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
  }

  private fun reloads(): Map<String, Long> =
      reader
          .collectAllMetrics()
          .filter { it.name == "runline.secrets.reloads" }
          .flatMap { it.longSumData.points }
          .associate {
            it.attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("result"))!! to
                it.value
          }

  @Test
  fun `the aliases are listed with their kind and status, and without a keystore the list is null`() {
    val file = keystores.pkcs12("list.p12", mapOf("Good" to "value", "Bad" to "café"))
    keystores.trustedCertificate(file, "ca", passwordFile)

    val listed = catalog(file).list()!!

    assertEquals(
        listOf(
            Triple("bad", EntryKind.SECRET, AliasStatus.INVALID_SECRET),
            Triple("ca", EntryKind.TRUSTED_CERTIFICATE, AliasStatus.FOUND),
            Triple("good", EntryKind.SECRET, AliasStatus.FOUND),
        ),
        listed.map { Triple(it.alias, it.kind, it.status) },
    )
    assertTrue(listed.all { it.usedBy.isEmpty() })
    assertNull(catalog(null).list())
  }

  @Test
  fun `each alias lists the resources that name it, whatever the case they wrote it in`() {
    val file = keystores.pkcs12("used.p12", mapOf("db-pass" to "value", "unused" to "value"))
    resourceUsing("warehouse", "DB-Pass")
    resourceUsing("reports", "db-pass")

    val listed = catalog(file).list()!!

    assertEquals(listOf("reports", "warehouse"), listed.single { it.alias == "db-pass" }.usedBy)
    assertEquals(emptyList(), listed.single { it.alias == "unused" }.usedBy)
  }

  @Test
  fun `a reload says how many aliases there are and which changed with who uses them`() {
    val file = keystores.pkcs12("reload.p12", mapOf("db-pass" to "old", "other" to "same"))
    resourceUsing("warehouse", "db-pass")
    val catalog = catalog(file)

    replaceWith(file) {
      keystores.deleteEntry(it, "db-pass", passwordFile)
      keystores.importSecret(it, "db-pass", "new", passwordFile)
    }
    val outcome = catalog.reload(root)

    assertEquals(
        SecretReloadOutcome.Reloaded(2, listOf(ChangedSecret("db-pass", listOf("warehouse")))),
        outcome,
    )
  }

  @Test
  fun `a reload that cannot read the file fails with the category and leaves the secrets`() {
    val file = keystores.pkcs12("broken.p12", mapOf("a" to "value"))
    val catalog = catalog(file)
    Files.writeString(file, "not a keystore\n".repeat(30))

    val outcome = catalog.reload(root)

    assertEquals(SecretReloadOutcome.Failed(OpenFailure.WRONG_FORMAT), outcome)
    assertEquals(listOf("a"), catalog.list()!!.map { it.alias })
  }

  @Test
  fun `a reload without a keystore says so`() {
    assertEquals(SecretReloadOutcome.NotConfigured, catalog(null).reload(root))
  }

  @Test
  fun `a reload is logged with the administrator and how it ended, and counted by result`() {
    val file = keystores.pkcs12("logged.p12", mapOf("a" to "marker-value-1"))
    val catalog = catalog(file)

    val lines =
        CapturedLogs().use { logs ->
          catalog.reload(root)
          Files.writeString(file, "not a keystore\n".repeat(30))
          catalog.reload(ApiIdentity("ops", Role.ADMIN))
          logs.lines
        }

    assertTrue(lines.any { it.contains("root") && it.contains("reloaded") }, "$lines")
    assertTrue(lines.any { it.contains("ops") && it.contains("wrong_format") }, "$lines")
    assertFalse(lines.any { it.contains("marker-value-1") })
    assertEquals(mapOf("ok" to 1L, "wrong_format" to 1L), reloads())
  }
}
