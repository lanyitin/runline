package dev.lawlan.runline.engine.resource

import ch.qos.logback.classic.Level
import dev.lawlan.runline.engine.config.TraceExportTuning
import dev.lawlan.runline.engine.secret.KeystoreSecretStore
import dev.lawlan.runline.engine.secret.SecretValue
import dev.lawlan.runline.engine.support.CapturedLogs
import dev.lawlan.runline.engine.support.Keystores
import dev.lawlan.runline.engine.support.OtlpReceiver
import dev.lawlan.runline.engine.support.QUICK_TRACE_EXPORT
import dev.lawlan.runline.engine.support.TraceExportRig
import java.net.ServerSocket
import java.nio.file.Files
import java.time.Duration
import kotlin.test.*

/**
 * A receiver that is down, slow, refusing or not there never costs a run anything (ADR-46 decision
 * 4): a submission returns at once, what cannot be exported is dropped and counted, and the Engine
 * says so in a warning that is limited in how often it is made. The receiver is a real one on a
 * real socket; "not there" is a port nobody listens on.
 */
class TraceExportFailureTest {
  private val keystores = Keystores()
  private val keystore = keystores.pkcs12("trace.p12", mapOf("langfuse-auth" to "Basic c2VjcmV0"))
  private val secrets =
      KeystoreSecretStore.open(
          keystore,
          SecretValue(Files.readString(keystores.passwordFile()).trim()),
      )
  private val receiver = OtlpReceiver()
  private val logs = CapturedLogs()
  private val rigs = mutableListOf<TraceExportRig>()

  private fun rig(tuning: TraceExportTuning = QUICK_TRACE_EXPORT) =
      TraceExportRig(secrets, tuning).also { rigs += it }

  @AfterTest
  fun close() {
    rigs.forEach { it.close() }
    receiver.close()
    logs.close()
    secrets.close()
  }

  private fun await(what: String, seconds: Long = 15, condition: () -> Boolean) {
    val deadline = System.nanoTime() + seconds * 1_000_000_000
    while (!condition()) {
      check(System.nanoTime() < deadline) { "gave up waiting for $what" }
      Thread.sleep(10)
    }
  }

  private fun warnings() = logs.at(Level.WARN).filter { it.contains("trace-export") }

  @Test
  fun `a receiver that answers with an error costs the batch, which is counted and warned about`() {
    receiver.answer = { 503 }
    val rig = rig()
    val binding = rig.bind(rig.resource(receiver.endpoint))

    rig.submit(binding, "s1", "a")
    rig.submit(binding, "s2", "b")

    await("the batch dropped") {
      rig.count("runline.trace_export.spans.dropped", "reason" to "export_failed") == 2L
    }
    assertEquals(0L, rig.count("runline.trace_export.spans.exported"))
    assertEquals(1L, rig.count("runline.trace_export.exports", "outcome" to "failed"))
    await("the warning") { warnings().isNotEmpty() }
    assertTrue(warnings().single().contains("langfuse"), warnings().toString())
  }

  @Test
  fun `a receiver that takes the request and never answers costs the batch after the timeout`() {
    receiver.hang = true
    val rig = rig(QUICK_TRACE_EXPORT.copy(exportTimeout = Duration.ofMillis(500)))
    val binding = rig.bind(rig.resource(receiver.endpoint))

    val started = System.nanoTime()
    rig.submit(binding, "s1", "a")
    val tookMillis = (System.nanoTime() - started) / 1_000_000

    assertTrue(tookMillis < 200, "a submission returned after $tookMillis ms")
    await("the batch dropped") {
      rig.count("runline.trace_export.spans.dropped", "reason" to "export_failed") == 1L
    }
    assertEquals(1, receiver.requests.size)
    assertEquals(0L, rig.count("runline.trace_export.spans.exported"))
  }

  @Test
  fun `a redirect is a failed export, not followed, and the credential goes nowhere else`() {
    val elsewhere = OtlpReceiver()
    try {
      receiver.answer = { 307 }
      receiver.location = elsewhere.endpoint
      val rig = rig()
      val binding = rig.bind(rig.resource(receiver.endpoint))

      rig.submit(binding, "s1", "a")
      await("the batch dropped") {
        rig.count("runline.trace_export.spans.dropped", "reason" to "export_failed") == 1L
      }
      Thread.sleep(300)

      assertEquals(1, receiver.requests.size)
      assertTrue(elsewhere.requests.isEmpty(), "the redirect was followed")
      await("the warning") { warnings().isNotEmpty() }
    } finally {
      elsewhere.close()
    }
  }

  @Test
  fun `a receiver that cannot be reached costs the batch`() {
    val closedPort = ServerSocket(0).use { it.localPort }
    val rig = rig()
    val binding = rig.bind(rig.resource("http://127.0.0.1:$closedPort/v1/traces"))

    rig.submit(binding, "s1", "a")

    await("the batch dropped") {
      rig.count("runline.trace_export.spans.dropped", "reason" to "export_failed") == 1L
    }
    await("the warning") { warnings().isNotEmpty() }
  }

  @Test
  fun `warnings are limited so that a receiver that is down does not flood the log`() {
    receiver.answer = { 500 }
    val rig = rig()
    val binding = rig.bind(rig.resource(receiver.endpoint))

    repeat(20) {
      rig.submit(binding, "s$it", "id$it")
      Thread.sleep(80)
    }
    await("every batch dropped") {
      rig.count("runline.trace_export.spans.dropped", "reason" to "export_failed") == 20L
    }

    assertEquals(1, warnings().size, warnings().toString())
  }

  @Test
  fun `a full queue drops new spans without blocking the run, and counts them`() {
    receiver.hang = true
    val rig =
        rig(
            QUICK_TRACE_EXPORT.copy(
                queueSize = 10,
                batchSize = 5,
                exportTimeout = Duration.ofSeconds(30),
            )
        )
    val binding = rig.bind(rig.resource(receiver.endpoint))

    val started = System.nanoTime()
    repeat(1000) { rig.submit(binding, "s$it", "id$it") }
    val tookMillis = (System.nanoTime() - started) / 1_000_000

    assertTrue(tookMillis < 1000, "1000 submissions took $tookMillis ms against a hung receiver")
    val dropped = rig.count("runline.trace_export.spans.dropped", "reason" to "queue_full")
    // One batch is with the hung request and at most ten wait in the queue: all the rest is gone.
    assertTrue(dropped in 985..995, "dropped $dropped")
  }

  @Test
  fun `a missing alias costs the batch and says so, without sending anything`() {
    val rig = rig()
    val binding = rig.bind(rig.resource(receiver.endpoint, alias = "no-such-alias"))

    rig.submit(binding, "s1", "a")

    await("the batch dropped") {
      rig.count("runline.trace_export.spans.dropped", "reason" to "key_unavailable") == 1L
    }
    assertTrue(receiver.requests.isEmpty())
    await("the warning") { warnings().isNotEmpty() }
  }

  @Test
  fun `a submission never fails the run, whatever it is given`() {
    val rig = rig()
    val binding = rig.bind(rig.resource(receiver.endpoint))

    val given =
        listOf<Map<String, Any?>>(
            emptyMap(),
            mapOf("name" to 5),
            mapOf("name" to "x", "id" to "y", "startTime" to "yesterday"),
        )

    for (arguments in given) {
      // The host reports a malformed call as a failed operation, never as a crash of the Engine.
      runCatching { binding.execute("trace.submit", HashMap(arguments)) }
          .exceptionOrNull()
          ?.let { assertIs<dev.lawlan.runline.accessors.ResourceOperationFailure>(it) }
    }
    assertEquals(0L, rig.count("runline.trace_export.spans.exported"))
  }
}
