package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.engine.config.TraceExportTuning
import dev.lawlan.runline.engine.secret.KeystoreSecretStore
import dev.lawlan.runline.engine.secret.ReloadResult
import dev.lawlan.runline.engine.secret.SecretValue
import dev.lawlan.runline.engine.support.CapturedLogs
import dev.lawlan.runline.engine.support.Keystores
import dev.lawlan.runline.engine.support.OtlpReceiver
import dev.lawlan.runline.engine.support.QUICK_TRACE_EXPORT
import dev.lawlan.runline.engine.support.TraceExportRig
import java.nio.file.Files
import java.time.Duration
import kotlin.test.*

/**
 * The rest of what a `trace-export` resource does with the spans it takes (ADR-46): limits on the
 * attributes of a span, the key being read when a batch goes out so that a reload of the keystore
 * is enough to rotate it, the wait at the end of a run, and what the Engine never says about a
 * span.
 */
class TraceExportBehaviorTest {
  private val keystores = Keystores()
  private val passwordFile = keystores.passwordFile()
  private val keystore = keystores.pkcs12("trace.p12", mapOf("langfuse-auth" to "Basic b2xk"))
  private val secrets =
      KeystoreSecretStore.open(keystore, SecretValue(Files.readString(passwordFile).trim()))
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

  // ---- limits ----

  @Test
  fun `attributes beyond the limit are dropped, and counted`() {
    val rig = rig(QUICK_TRACE_EXPORT.copy(maxAttributes = 3))
    val binding = rig.bind(rig.resource(receiver.endpoint))

    rig.submit(binding, "s", "a", attributes = (1..5).associate { "k$it" to it.toLong() })
    await("the span") { receiver.spans.isNotEmpty() }

    assertEquals(setOf("k1", "k2", "k3"), receiver.spans.single().attributes.keys)
    assertEquals(2L, rig.count("runline.trace_export.attributes.truncated", "kind" to "count"))
  }

  @Test
  fun `text beyond the limit is cut, and counted`() {
    val rig = rig(QUICK_TRACE_EXPORT.copy(maxAttributeLength = 10))
    val binding = rig.bind(rig.resource(receiver.endpoint))

    rig.submit(
        binding,
        "name-longer-than-ten",
        "a",
        attributes =
            mapOf(
                "long" to "0123456789ABCDEF",
                "list" to listOf("0123456789ABCDEF", "short"),
                "fits" to "0123456789",
            ),
    )
    await("the span") { receiver.spans.isNotEmpty() }

    val span = receiver.spans.single()
    assertEquals("name-longe", span.name)
    assertEquals("0123456789", span.attributes["long"])
    assertEquals(listOf("0123456789", "short"), span.attributes["list"])
    assertEquals("0123456789", span.attributes["fits"])
    assertEquals(3L, rig.count("runline.trace_export.attributes.truncated", "kind" to "length"))
  }

  // ---- key rotation ----

  @Test
  fun `after the keystore is reloaded the next batch is sent with the new key`() {
    val rig = rig()
    val binding = rig.bind(rig.resource(receiver.endpoint))
    rig.submit(binding, "before", "a")
    await("the first batch") { receiver.requests.size == 1 }

    keystores.deleteEntry(keystore, "langfuse-auth", passwordFile)
    keystores.importSecret(keystore, "langfuse-auth", "Basic bmV3", passwordFile)
    assertIs<ReloadResult.Reloaded>(secrets.reload())
    // The run holds the binding it was given; it does not have to be rebound.
    rig.submit(binding, "after", "b")
    await("the second batch") { receiver.requests.size == 2 }

    assertEquals("Basic b2xk", receiver.requests[0].authorization)
    assertEquals("Basic bmV3", receiver.requests[1].authorization)
  }

  // ---- the end of a run ----

  @Test
  fun `closing the binding returns at once, and what is queued is sent without waiting for the schedule`() {
    val rig = rig(QUICK_TRACE_EXPORT.copy(scheduleDelay = Duration.ofMinutes(10)))
    val binding = rig.bind(rig.resource(receiver.endpoint))
    rig.submit(binding, "s1", "a")
    rig.submit(binding, "s2", "b")
    assertTrue(receiver.spans.isEmpty())

    binding.close()

    await("both spans at the receiver") { receiver.spans.size == 2 }
    await("the export counted") { rig.count("runline.trace_export.spans.exported") == 2L }
  }

  @Test
  fun `closing the binding does not wait for a receiver that does not answer, and gives up on it later, counted`() {
    receiver.hang = true
    val rig =
        rig(
            QUICK_TRACE_EXPORT.copy(
                scheduleDelay = Duration.ofMinutes(10),
                exportTimeout = Duration.ofSeconds(30),
                flushWait = Duration.ofMillis(300),
            )
        )
    val binding = rig.bind(rig.resource(receiver.endpoint))
    rig.submit(binding, "s1", "a")

    val started = System.nanoTime()
    binding.close()
    val tookMillis = (System.nanoTime() - started) / 1_000_000

    assertTrue(tookMillis < 150, "close took $tookMillis ms")
    await("the span given up") {
      rig.count("runline.trace_export.spans.dropped", "reason" to "export_failed") +
          rig.count("runline.trace_export.spans.dropped", "reason" to "abandoned") == 1L
    }
  }

  @Test
  fun `a list with more elements than the limit is cut, counted, and its content is nowhere else`() {
    val rig = rig(QUICK_TRACE_EXPORT.copy(maxAttributeElements = 4))
    val binding = rig.bind(rig.resource(receiver.endpoint))

    rig.submit(
        binding,
        "s",
        "a",
        attributes = mapOf("many" to (1..100_000).map { "element-$it" }, "few" to listOf(1L, 2L)),
    )
    await("the span") { receiver.spans.isNotEmpty() }

    val attributes = receiver.spans.single().attributes
    assertEquals(listOf("element-1", "element-2", "element-3", "element-4"), attributes["many"])
    assertEquals(listOf(1L, 2L), attributes["few"])
    assertEquals(1L, rig.count("runline.trace_export.attributes.truncated", "kind" to "elements"))
    assertFalse((logs.lines.joinToString() + rig.recordedText()).contains("element-"))
  }

  @Test
  fun `a run that starts while the Engine is going down still gets a binding, which exports nothing`() {
    val rig = rig()
    rig.exporters.close()

    val binding = rig.bind(rig.resource(receiver.endpoint))
    rig.submit(binding, "s1", "a")
    binding.close()

    assertEquals(1L, rig.count("runline.trace_export.spans.dropped", "reason" to "unavailable"))
    assertTrue(receiver.requests.isEmpty())
  }

  // ---- what is never said ----

  @Test
  fun `neither the credential nor the content of a span is in a log line, a metric or an error`() {
    receiver.answer = { 500 }
    val rig = rig()
    val binding = rig.bind(rig.resource(receiver.endpoint))
    val secretText = "prompt: tell me the launch codes 4711"

    rig.submit(
        binding,
        "name-with-$secretText",
        "a",
        statusMessage = secretText,
        status = "ERROR",
        attributes = mapOf("llm.prompt" to secretText, "secret.key.name" to "value-$secretText"),
    )
    await("the batch dropped") {
      rig.count("runline.trace_export.spans.dropped", "reason" to "export_failed") == 1L
    }
    binding.close()

    val everything = logs.lines.joinToString("\n") + rig.recordedText()
    assertFalse(everything.contains("launch codes"), everything)
    assertFalse(everything.contains("4711"), everything)
    assertFalse(everything.contains("llm.prompt"), everything)
    assertFalse(everything.contains("Basic b2xk"), everything)
    assertFalse(everything.contains("b2xk"), everything)
  }
}
