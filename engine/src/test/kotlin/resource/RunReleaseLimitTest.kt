package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.accessors.fake.FreezableForward
import dev.lawlan.runline.accessors.jdbc.JdbcPools
import dev.lawlan.runline.accessors.jdbc.RealPostgres
import dev.lawlan.runline.analyzer.AllowListEntry
import dev.lawlan.runline.engine.auth.ApiIdentity
import dev.lawlan.runline.engine.auth.Role
import dev.lawlan.runline.engine.run.RunState
import dev.lawlan.runline.engine.secret.KeystoreSecretStore
import dev.lawlan.runline.engine.secret.SecretValue
import dev.lawlan.runline.engine.support.CapturedLogs
import dev.lawlan.runline.engine.support.Keystores
import dev.lawlan.runline.engine.support.RunHarness
import dev.lawlan.runline.engine.support.RunHarness.Companion.usingTyped
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import kotlin.test.*

/**
 * The end of a run cannot hang the scheduler (ADR-007 "Run 終止時", WI-62). A run holds a connection
 * of a `jdbc-pool` resource in an open transaction, and the network between the Engine and the
 * database stops answering just before the run ends, so giving the connection back cannot finish.
 * Everything is real: PostgreSQL behind a real TCP forward that is frozen, the coordinator,
 * scheduler and Runner, a PKCS12 keystore; what is looked at is the run store the API reads, the
 * coordinator's holders and waiters, the Engine's log and its metrics.
 */
class RunReleaseLimitTest {
  private val keystores = Keystores()
  private val database = RealPostgres.newDatabase()
  private val role = "rel_" + UUID.randomUUID().toString().replace("-", "").take(12)
  private val password = "pw-" + UUID.randomUUID().toString().replace("-", "")
  private val keystore: Path = keystores.pkcs12("release.p12", mapOf("db-pw" to password))
  private val secrets =
      KeystoreSecretStore.open(
          keystore,
          SecretValue(Files.readString(keystores.passwordFile()).trim()),
      )
  private val forward = FreezableForward(RealPostgres.host, RealPostgres.port)
  private val metrics = InMemoryMetricReader.create()
  private val logs = CapturedLogs()
  private val harnesses = mutableListOf<RunHarness>()

  init {
    database.createRole(role, password, "GRANT ALL ON SCHEMA public TO $role")
  }

  @AfterTest
  fun closeAll() {
    forward.close()
    harnesses.forEach { it.close() }
    logs.close()
    secrets.close()
    database.close()
  }

  private fun harness(
      maxConcurrent: Int,
      releaseWait: Duration,
      shutdownGrace: Duration = Duration.ofSeconds(5),
  ) =
      RunHarness(
              maxConcurrent = maxConcurrent,
              resourceWaitTimeout = Duration.ofHours(1),
              secrets = secrets,
              releaseWait = releaseWait,
              shutdownGrace = shutdownGrace,
              openTelemetry =
                  OpenTelemetrySdk.builder()
                      .setMeterProvider(
                          SdkMeterProvider.builder().registerMetricReader(metrics).build()
                      )
                      .build(),
              allowList =
                  listOf("java.lang", "java.util", "java.io", "kotlin", "org.jetbrains.annotations")
                      .map { AllowListEntry(it) },
          )
          .also {
            harnesses += it
            it.defineJdbc(
                "db",
                """{"kind":"postgresql","host":"${forward.host}","port":${forward.port},"database":"${database.name}","username":"$role"}""",
                alias = "db-pw",
            )
          }

  /**
   * Starts a run that holds `db` with a transaction open until it is told to end, and a run that
   * waits for `db`; returns them in that order.
   */
  private fun RunHarness.holderAndWaiter(): Pair<UUID, UUID> {
    val holder =
        upload(
            "holder",
            """
            JdbcAccessor db = context.getAccessors().jdbcPool("db");
            db.begin();
            db.query("SELECT 1");
            context.getFiles().writeText(FileScope.PIPELINE_SHARED, "in-transaction", "x");
            try {
              while (!context.getFiles().exists(FileScope.PIPELINE_SHARED, "end")) Thread.sleep(10);
            } catch (InterruptedException e) { throw new RuntimeException(e); }
            """
                .trimIndent(),
            declaration = usingTyped("db" to "jdbc-pool"),
        )
    val waiter =
        upload(
            "waiter",
            """
            JdbcAccessor db = context.getAccessors().jdbcPool("db");
            db.query("SELECT 1");
            """
                .trimIndent(),
            declaration = usingTyped("db" to "jdbc-pool"),
        )
    val held = start(holder, "holder")
    awaitFile(shared("holder", "in-transaction"))
    val waiting = start(waiter, "waiter")
    // With a slot of its own it waits for `db`; with none it waits for a slot first.
    await(waiting, if (maxConcurrent > 1) RunState.WAITING_FOR_RESOURCES else RunState.QUEUED)
    return held to waiting
  }

  /** Freezes the network to the database, then lets the holder end; returns when that was. */
  private fun RunHarness.endWithFrozenNetwork(): Long {
    forward.freeze()
    Files.writeString(shared("holder", "end"), "x")
    return System.nanoTime()
  }

  private fun RunHarness.holds(run: UUID) =
      coordinator!!.activity("db").holders.any { it.runId == run }

  /** The release failures the metrics counted, by outcome and resource type. */
  private fun releaseFailures(): Map<Pair<String?, String?>, Long> =
      metrics
          .collectAllMetrics()
          .filter { it.name == "runline.runs.release.failures" }
          .flatMap { it.longSumData.points }
          .associate { (it.attributes.get(OUTCOME) to it.attributes.get(TYPE)) to it.value }
          .filterValues { it > 0 }

  private fun errorsAbout(run: UUID) = logs.lines.filter { it.startsWith("ERROR") && "$run" in it }

  @Test
  fun `a run whose release hangs is recorded as ended after the release wait, and what it held stays held until the release ends`() {
    val h = harness(maxConcurrent = 1, releaseWait = Duration.ofSeconds(1))
    val other = h.upload("other", "")
    val (holder, waiter) = h.holderAndWaiter()

    val ended = h.endWithFrozenNetwork()
    val record = h.awaitEnd(holder)
    val millis = (System.nanoTime() - ended) / 1_000_000

    assertEquals(RunState.SUCCEEDED, record.state, record.failure?.message)
    assertTrue(millis >= 900, "recorded as ended after $millis ms, before the release wait")
    assertTrue(h.holds(holder), "the capacity was given back before the release ended")
    // Its slot is free: with one slot, the waiter gets as far as waiting for `db`, and another run
    // starts and ends.
    h.await(waiter, RunState.WAITING_FOR_RESOURCES)
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(h.start(other, "other")).state)
    assertEquals(
        mapOf<Pair<String?, String?>, Long>(("timed_out" to "jdbc-pool") to 1L),
        releaseFailures(),
    )
    assertTrue(errorsAbout(holder).isNotEmpty(), "no error was logged about $holder")

    forward.thaw()
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(waiter).state)
    assertFalse(h.holds(holder))
    assertEquals(
        mapOf<Pair<String?, String?>, Long>(("timed_out" to "jdbc-pool") to 1L),
        releaseFailures(),
    )
  }

  @Test
  fun `a release that fails within the limit of the type ends the run with the failure said, and other runs go on meanwhile`() {
    val h = harness(maxConcurrent = 2, releaseWait = Duration.ofSeconds(30))
    val other = h.upload("other", "")
    val (holder, waiter) = h.holderAndWaiter()

    val ended = h.endWithFrozenNetwork()
    val meanwhile = h.awaitEnd(h.start(other, "other"))
    val record = h.awaitEnd(holder)
    val millis = (System.nanoTime() - ended) / 1_000_000

    assertEquals(RunState.SUCCEEDED, meanwhile.state)
    assertTrue(
        meanwhile.finishedAt!! < record.finishedAt!!,
        "the other run ended only after the release was over",
    )
    assertEquals(RunState.SUCCEEDED, record.state, record.failure?.message)
    assertTrue(millis < JdbcPools.RESET_LIMIT_MILLIS + 10_000, "it took $millis ms")
    assertFalse(h.holds(holder), "the release is over, failed, and nothing is held any more")
    assertEquals(
        mapOf<Pair<String?, String?>, Long>(("failed" to "jdbc-pool") to 1L),
        releaseFailures(),
    )
    assertTrue(errorsAbout(holder).isNotEmpty(), "no error was logged about $holder")
    assertTrue(logs.lines.none { password in it }, "the password is never logged")

    forward.thaw()
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(waiter).state)
  }

  @Test
  fun `a resource released by force whose connection cannot be cleaned is taken off the run all the same, and that is logged`() {
    val h = harness(maxConcurrent = 2, releaseWait = Duration.ofSeconds(30))
    val (holder, waiter) = h.holderAndWaiter()
    forward.freeze()

    val result = h.coordinator!!.forceRelease("db", holder, ApiIdentity("root", Role.ADMIN))

    assertIs<ForceReleaseResult.Released>(result)
    assertFalse(h.holds(holder))
    assertTrue(errorsAbout(holder).isNotEmpty(), "no error was logged about $holder")
    forward.thaw()
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(waiter).state)
    Files.writeString(h.shared("holder", "end"), "x")
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(holder).state)
  }

  /** Starts a run that holds `db` in an open transaction; [then] is what it does after that. */
  private fun RunHarness.inTransaction(name: String, then: String): UUID {
    val hash =
        upload(
            name,
            """
            JdbcAccessor db = context.getAccessors().jdbcPool("db");
            db.begin();
            db.query("SELECT 1");
            context.getFiles().writeText(FileScope.PIPELINE_SHARED, "in-transaction", "x");
            $then
            """
                .trimIndent(),
            declaration = usingTyped("db" to "jdbc-pool"),
        )
    val run = start(hash, name)
    awaitFile(shared(name, "in-transaction"))
    return run
  }

  @Test
  fun `while the Engine shuts down, a release is waited for within the grace time, not the release wait`() {
    val h = harness(maxConcurrent = 1, releaseWait = Duration.ofSeconds(1), Duration.ofSeconds(20))
    val holder = h.inTransaction("holder", RunHarness.WAIT_FOR_STOP)
    forward.freeze()

    val began = System.nanoTime()
    h.scheduler.close()
    val millis = (System.nanoTime() - began) / 1_000_000

    // The release ended, failed, within the limit of the type, before the shutdown went on.
    assertFalse(h.holds(holder), "the shutdown went on after $millis ms, with db still held")
    assertEquals(
        mapOf<Pair<String?, String?>, Long>(("failed" to "jdbc-pool") to 1L),
        releaseFailures(),
    )
    assertEquals(RunState.INTERRUPTED, h.awaitEnd(holder).state)
    assertTrue(millis < 20_000, "the shutdown took $millis ms, past its grace time")
  }

  @Test
  fun `a run that fails and whose release fails too ends with its own failure`() {
    val h = harness(maxConcurrent = 1, releaseWait = Duration.ofSeconds(30))
    val run =
        h.inTransaction(
            "failing",
            """
            try {
              while (!context.getFiles().exists(FileScope.PIPELINE_SHARED, "end")) Thread.sleep(10);
            } catch (InterruptedException e) { throw new RuntimeException(e); }
            throw new IllegalStateException("the run's own failure");
            """
                .trimIndent(),
        )
    forward.freeze()
    Files.writeString(h.shared("failing", "end"), "x")

    val record = h.awaitEnd(run)

    assertEquals(RunState.FAILED, record.state)
    assertEquals(IllegalStateException::class.java.name, record.failure?.type)
    assertEquals("the run's own failure", record.failure?.message)
    // The failure of the release is said too, apart from the run's.
    assertEquals(
        mapOf<Pair<String?, String?>, Long>(("failed" to "jdbc-pool") to 1L),
        releaseFailures(),
    )
    assertTrue(errorsAbout(run).isNotEmpty(), "no error was logged about $run")
  }

  private companion object {
    val OUTCOME: AttributeKey<String> = AttributeKey.stringKey("outcome")
    val TYPE: AttributeKey<String> = AttributeKey.stringKey("type")
  }
}
