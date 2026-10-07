package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.accessors.jdbc.RealPostgres
import dev.lawlan.runline.engine.run.RunState
import dev.lawlan.runline.engine.secret.KeystoreSecretStore
import dev.lawlan.runline.engine.secret.SecretValue
import dev.lawlan.runline.engine.support.CapturedLogs
import dev.lawlan.runline.engine.support.Keystores
import dev.lawlan.runline.engine.support.RunHarness
import dev.lawlan.runline.engine.support.RunHarness.Companion.usingTyped
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import java.nio.file.Files
import java.time.Duration
import java.util.UUID
import kotlin.test.*

/**
 * The end-to-end case of the secret non-leak rules for `jdbc-pool` (WI-48, WI-41): the password is
 * a real entry of a real keystore, the connection is a real connection, and the database says it
 * back where a database can: an account whose name is the password, so that the server's refusal to
 * log in names it. Whatever the Engine says is searched for the password, and for the SQL text and
 * the parameters of statements, which no log or trace may hold either.
 */
class JdbcKeyLeakTest {
  private val password = "Pw-Leak-" + UUID.randomUUID().toString().replace("-", "")
  private val sqlMarker = "sql_marker_9F3A"
  private val valueMarker = "value-marker-77C1"
  private val keystores = Keystores()
  private val passwordFile = keystores.passwordFile()
  private val keystore = keystores.pkcs12("jdbc-leak.p12", mapOf("db-pw" to password))
  private val secrets =
      KeystoreSecretStore.open(keystore, SecretValue(Files.readString(passwordFile).trim()))
  private val target = RealPostgres.newDatabase()
  private val logs = CapturedLogs()
  private val spans = InMemorySpanExporter.create()
  private val metrics = InMemoryMetricReader.create()
  private val otel =
      OpenTelemetrySdk.builder()
          .setTracerProvider(
              SdkTracerProvider.builder()
                  .addSpanProcessor(SimpleSpanProcessor.create(spans))
                  .build()
          )
          .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metrics).build())
          .build()
  private val h =
      RunHarness(
          maxConcurrent = 2,
          resourceWaitTimeout = Duration.ofHours(1),
          openTelemetry = otel,
          secrets = secrets,
          jdbcObserverFor = { JdbcTelemetry(otel, it) },
      )
  private val runs = mutableListOf<UUID>()

  init {
    // An account named like the password, with another password: logging in as it fails, and the
    // server's words about that failure name the account.
    target.execute("CREATE ROLE \"$password\" LOGIN PASSWORD 'something-else'")
    target.execute("GRANT CONNECT ON DATABASE ${target.name} TO \"$password\"")
    h.defineJdbc(
        "db",
        """{"kind":"postgresql","host":"${RealPostgres.host}","port":${RealPostgres.port},"database":"${target.name}","username":"$password"}""",
        alias = "db-pw",
    )
  }

  @AfterTest
  fun close() {
    h.close()
    logs.close()
    secrets.close()
    target.close()
  }

  private fun run(name: String, body: String): dev.lawlan.runline.engine.run.RunRecord {
    val hash = h.upload(name, body, declaration = usingTyped("db" to "jdbc-pool"))
    val id = h.start(hash, name)
    runs += id
    return h.awaitEnd(id)
  }

  /** Everything the Engine says about the runs so far, as text. */
  private fun everything(): String = buildString {
    runs.forEach { id ->
      val record = h.record(id)
      append(record.failure.toString()).append('\n')
      append(h.runStore.read(id, 0, 10_000).joinToString("\n") { it.line }).append('\n')
    }
    append(logs.lines.joinToString("\n")).append('\n')
    spans.finishedSpanItems.forEach { append(it.toString()).append(it.events).append('\n') }
    metrics.collectAllMetrics().forEach { append(it.toString()).append('\n') }
  }

  @Test
  fun `a refusal that names the account, so the pipeline is told the category and no surface has the password`() {
    val caught =
        run(
            "catches",
            """
            JdbcAccessor db = context.getAccessors().jdbcPool("db");
            String seen;
            try { db.query("SELECT 1"); seen = "no error"; }
            catch (ResourceAccessException e) {
              seen = e.getFailure().name() + "|" + e.getSqlState() + "|" + e.getMessage() + "|" + e.getErrorId() + "|" + e.getCause() + "|" + e;
            }
            context.getFiles().writeText(FileScope.PIPELINE_SHARED, "seen", seen);
            """
                .trimIndent(),
        )
    val escaped =
        run(
            "escapes",
            """
            JdbcAccessor db = context.getAccessors().jdbcPool("db");
            db.query("SELECT 1");
            """
                .trimIndent(),
        )

    assertEquals(RunState.SUCCEEDED, caught.state, caught.failure?.message)
    val seen = Files.readString(h.shared("catches", "seen"))
    assertTrue(seen.startsWith("DENIED|28P01|"), seen)
    assertFalse(seen.contains(password), seen)
    assertEquals(RunState.FAILED, escaped.state)
    assertTrue(escaped.failure!!.message!!.contains("DENIED"), escaped.failure!!.message)
    val said = everything()
    assertFalse(said.contains(password), "the password is in something the Engine said")
    assertFalse(said.contains(password.lowercase()), "the password is in something the Engine said")
  }

  @Test
  fun `a statement that fails leaves neither its text nor its parameters in a log, a trace or a metric`() {
    val plain = "pw-" + UUID.randomUUID().toString().replace("-", "")
    val role = "plain_" + UUID.randomUUID().toString().replace("-", "").take(10)
    target.createRole(role, plain, "GRANT ALL ON SCHEMA public TO $role")
    keystores.importSecret(keystore, "plain-pw", plain, passwordFile)
    secrets.reload()
    h.defineJdbc(
        "plain",
        """{"kind":"postgresql","host":"${RealPostgres.host}","port":${RealPostgres.port},"database":"${target.name}","username":"$role"}""",
        alias = "plain-pw",
    )
    val failing =
        run(
            "fails",
            """
            JdbcAccessor db = context.getAccessors().jdbcPool("db");
            JdbcAccessor plain = context.getAccessors().jdbcPool("plain");
            plain.update("CREATE TABLE $sqlMarker (a text PRIMARY KEY)");
            plain.update("INSERT INTO $sqlMarker VALUES (?)", java.util.Arrays.<Object>asList("$valueMarker"));
            plain.update("INSERT INTO $sqlMarker VALUES (?)", java.util.Arrays.<Object>asList("$valueMarker"));
            """
                .trimIndent(),
        )

    assertEquals(RunState.FAILED, failing.state)
    val said = everything()
    assertFalse(said.contains(plain), "the password is in something the Engine said")
    assertFalse(said.contains(sqlMarker), "a table of a statement is in something the Engine said")
    assertFalse(said.contains(valueMarker), "a parameter is in something the Engine said")
    assertFalse(said.contains("INSERT INTO"), "SQL text is in something the Engine said")
  }
}
