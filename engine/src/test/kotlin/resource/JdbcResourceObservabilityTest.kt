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
 * What the Engine records of the statements on a `jdbc-pool` resource (ADR-019 decision 10, WI-48):
 * the connections in use, the connections that could not be had and the time statements took,
 * labelled by the resource's name and type and nothing else; a span per statement in the run's
 * trace, and the log's lines. No SQL text and no parameter is in any of them.
 */
class JdbcResourceObservabilityTest {
  private val keystores = Keystores()
  private val passwordFile = keystores.passwordFile()
  private val role = "obs_" + UUID.randomUUID().toString().replace("-", "").take(12)
  private val password = "pw-" + UUID.randomUUID().toString().replace("-", "")
  private val keystore = keystores.pkcs12("jdbc-obs.p12", mapOf("db-pw" to password))
  private val secrets =
      KeystoreSecretStore.open(keystore, SecretValue(Files.readString(passwordFile).trim()))
  private val target = RealPostgres.newDatabase()
  private val spans = InMemorySpanExporter.create()
  private val metrics = InMemoryMetricReader.create()
  private val logs = CapturedLogs()
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
  private val sqlMarker = "sql_marker_41D2"
  private val valueMarker = "value-marker-9B8E"

  init {
    target.createRole(role, password, "GRANT ALL ON SCHEMA public TO $role")
    h.defineJdbc("db", settings(role), alias = "db-pw")
  }

  @AfterTest
  fun close() {
    h.close()
    logs.close()
    secrets.close()
    target.close()
  }

  private fun settings(user: String) =
      """{"kind":"postgresql","host":"${RealPostgres.host}","port":${RealPostgres.port},"database":"${target.name}","username":"$user"}"""

  private fun run(name: String, body: String) =
      h.awaitEnd(h.start(h.upload(name, body, declaration = usingTyped("db" to "jdbc-pool")), name))

  private fun points(name: String) = metrics.collectAllMetrics().firstOrNull { it.name == name }

  private val statements =
      """
      JdbcAccessor db = context.getAccessors().jdbcPool("db");
      db.update("CREATE TABLE $sqlMarker (a text PRIMARY KEY)");
      db.update("INSERT INTO $sqlMarker VALUES (?)", java.util.Arrays.<Object>asList("$valueMarker"));
      try { db.update("INSERT INTO $sqlMarker VALUES (?)", java.util.Arrays.<Object>asList("$valueMarker")); }
      catch (ResourceAccessException e) { }
      """
          .trimIndent()

  @Test
  fun `statements are timed by operation and outcome with the resource and type as the only other labels`() {
    val ended = run("timed", statements)

    assertEquals(RunState.SUCCEEDED, ended.state, ended.failure?.message)
    val histogram = points("runline.resources.jdbc.statement.duration")!!.histogramData.points
    val seen = histogram.map {
      it.attributes.asMap().mapKeys { k -> k.key.key }.mapValues { "${it.value}" }
    }
    assertTrue(seen.all { it.keys == setOf("resource", "type", "operation", "outcome") }, "$seen")
    assertTrue(seen.all { it["resource"] == "db" && it["type"] == "jdbc-pool" }, "$seen")
    assertEquals(
        setOf("ok" to "jdbc.update", "sql_error" to "jdbc.update"),
        seen.map { it["outcome"]!! to it["operation"]!! }.toSet(),
    )
    assertEquals(3, histogram.sumOf { it.count })
  }

  @Test
  fun `a connection that cannot be had is counted by kind, and the connections in use are reported while a run holds them`() {
    h.defineJdbc(
        "nobody",
        settings("no_such_account_${UUID.randomUUID().toString().take(6)}"),
        alias = "db-pw",
    )
    val hold =
        h.upload(
            "holder",
            """
            JdbcAccessor db = context.getAccessors().jdbcPool("db");
            db.query("SELECT 1");
            context.getFiles().writeText(FileScope.PIPELINE_SHARED, "held", "x");
            try { while (!context.getFiles().exists(FileScope.PIPELINE_SHARED, "release")) Thread.sleep(10); }
            catch (InterruptedException e) { throw new RuntimeException(e); }
            """
                .trimIndent(),
            declaration = usingTyped("db" to "jdbc-pool"),
        )
    val denied =
        h.upload(
            "denied",
            """
            JdbcAccessor db = context.getAccessors().jdbcPool("nobody");
            try { db.query("SELECT 1"); } catch (ResourceAccessException e) { }
            """
                .trimIndent(),
            declaration = usingTyped("nobody" to "jdbc-pool"),
        )
    val holder = h.start(hold, "holder")
    h.awaitFile(h.shared("holder", "held"))

    val active = points("runline.resources.jdbc.connections.active")!!.longGaugeData.points
    assertEquals(1L, active.single { it.attributes.asMap().values.contains("db") }.value)
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(h.start(denied, "denied")).state)
    Files.writeString(h.shared("holder", "release"), "x")
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(holder).state)

    val failures = points("runline.resources.jdbc.acquire.failures")!!.longSumData.points
    val point = failures.single()
    assertEquals(
        mapOf("resource" to "nobody", "type" to "jdbc-pool", "kind" to "denied"),
        point.attributes.asMap().mapKeys { it.key.key }.mapValues { "${it.value}" },
    )
    assertEquals(1L, point.value)
  }

  @Test
  fun `a span per statement in the run's trace, and neither it nor the logs nor the metrics hold SQL or a value`() {
    val ended = run("traced", statements)

    assertEquals(RunState.SUCCEEDED, ended.state, ended.failure?.message)
    val statementSpans =
        spans.finishedSpanItems.filter { it.name == "runline.resource.jdbc.update" }
    assertEquals(3, statementSpans.size)
    assertTrue(
        statementSpans.all {
          it.attributes
              .asMap()
              .mapKeys { k -> k.key.key }
              .keys
              .containsAll(listOf("runline.resource.name", "runline.resource.type"))
        }
    )
    val everything = buildString {
      spans.finishedSpanItems.forEach { append(it).append(it.events).append('\n') }
      metrics.collectAllMetrics().forEach { append(it).append('\n') }
      append(logs.lines.joinToString("\n")).append('\n')
      append(h.runStore.read(ended.id, 0, 10_000).joinToString("\n") { it.line })
    }
    assertFalse(everything.contains(sqlMarker), "a table of a statement is in something")
    assertFalse(everything.contains(valueMarker), "a parameter is in something")
    assertFalse(everything.contains("INSERT INTO"), "SQL text is in something")
    assertFalse(everything.contains(password), "the password is in something")
  }
}
