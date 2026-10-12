package dev.lawlan.runline.engine.trigger

import ch.qos.logback.classic.Level
import dev.lawlan.runline.engine.config.WebhookSettings
import dev.lawlan.runline.engine.fixtures.IngestPipeline
import dev.lawlan.runline.engine.run.RunState
import dev.lawlan.runline.engine.support.CapturedLogs
import dev.lawlan.runline.engine.support.RunHarness
import dev.lawlan.runline.engine.support.TriggerRig
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.*

/** Webhook calls whose body carries parameters the trigger lets the caller provide (ADR-036). */
class WebhookCallerParametersTest {
  private val rig = TriggerRig()
  private val h = rig.harness
  private val logs = CapturedLogs()
  private val maxBody = 200L
  private val receiver =
      WebhookReceiver(
          rig.store,
          rig.firer,
          rig.telemetry,
          rig.clock,
          h.definitions,
          WebhookSettings(maxBody),
      )
  private val hash = h.uploadKotlin(IngestPipeline::class.java)

  /** A body that notes whether anybody opened it, and how many bytes were taken from it. */
  private class Body(text: String) {
    private val bytes = text.toByteArray()
    var opened = false
    var taken = 0L

    val source: () -> InputStream = {
      opened = true
      object : InputStream() {
        private val inner = ByteArrayInputStream(bytes)

        override fun read(): Int = inner.read().also { if (it >= 0) taken++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            inner.read(b, off, len).also { if (it > 0) taken += it }
      }
    }
  }

  private fun hook(
      name: String = "on-upload",
      caller: List<String> = listOf("file"),
      fixed: Map<String, String> = mapOf("owner" to "team"),
  ) = rig.webhook(name, hash, "ingest", fixed, callerParameters = caller)

  private fun firings(name: String = "on-upload") =
      rig.store.firings(rig.store.find(name)!!.id, 1000)

  private fun rejections() = rig.counter("runline.triggers.webhook.rejected")

  @AfterTest
  fun close() {
    logs.close()
    rig.close()
  }

  // ---- an empty list changes nothing ----

  @Test
  fun `a webhook without caller parameters never opens the body`() {
    val (_, secret) = hook(caller = emptyList(), fixed = mapOf("file" to "a", "owner" to "team"))
    val body = Body("""{"file":"b"}""")

    assertEquals(WebhookResult.Accepted, receiver.receive("on-upload", secret, "d-1", body.source))

    assertFalse(body.opened)
    val run = h.await(checkNotNull(firings().single().runId), RunState.SUCCEEDED)
    assertEquals(mapOf("file" to "a", "owner" to "team", "mode" to "fast"), run.parameters)
  }

  // ---- accepting ----

  @Test
  fun `the run gets the fixed parameters plus the values of the caller`() {
    val (_, secret) = hook()

    val result =
        receiver.receive("on-upload", secret, "d-1", Body("""{"file":"videos/a.mp4"}""").source)

    assertEquals(WebhookResult.Accepted, result)
    val firing = firings().single()
    assertEquals(FiringOutcome.RUN_CREATED, firing.outcome)
    val run = h.await(checkNotNull(firing.runId), RunState.SUCCEEDED)
    assertEquals(
        mapOf("file" to "videos/a.mp4", "owner" to "team", "mode" to "fast"),
        run.parameters,
    )
  }

  @Test
  fun `a caller parameter that is optional can be left out or given`() {
    val (_, secret) = hook(caller = listOf("file", "mode"))

    receiver.receive("on-upload", secret, "d-1", Body("""{"file":"a"}""").source)
    receiver.receive("on-upload", secret, "d-2", Body("""{"file":"a","mode":"slow"}""").source)

    val modes =
        firings().map { h.await(checkNotNull(it.runId), RunState.SUCCEEDED).parameters["mode"] }
    assertEquals(setOf("fast", "slow"), modes.toSet())
  }

  @Test
  fun `the usual checks of a run still apply to what the caller gave`() {
    val unsafe =
        h.upload(
            "risky",
            "java.nio.file.Files.exists(java.nio.file.Path.of(\"x\"));",
            declaration =
                RunHarness.DEFAULT_DECLARATION +
                    ", parameters = {@Param(name = \"file\"), @Param(name = \"owner\")}",
        )
    val (_, secret) =
        rig.webhook(
            "risky-hook",
            unsafe,
            "risky",
            mapOf("owner" to "t"),
            callerParameters = listOf("file"),
        )

    val result = receiver.receive("risky-hook", secret, "d-1", Body("""{"file":"a"}""").source)

    assertEquals(WebhookResult.Accepted, result)
    assertEquals(0, h.runCount())
    assertEquals("unsafe_not_allowed", firings("risky-hook").single().reason)
  }

  // ---- order: credentials before the body ----

  @Test
  fun `a wrong secret or a bad delivery identifier is answered as before and the body is not opened`() {
    val (_, secret) = hook()
    val body = Body("""{"file":"a"}""")

    assertEquals(
        WebhookResult.Unauthorized,
        receiver.receive("on-upload", "wrong", "d-1", body.source),
    )
    assertEquals(
        WebhookResult.Unauthorized,
        receiver.receive("on-upload", null, "d-1", body.source),
    )
    assertEquals(WebhookResult.Unauthorized, receiver.receive("nobody", secret, "d-1", body.source))
    assertEquals(
        WebhookResult.InvalidDelivery,
        receiver.receive("on-upload", secret, null, body.source),
    )
    assertEquals(
        WebhookResult.InvalidDelivery,
        receiver.receive("on-upload", secret, "a b", body.source),
    )

    assertFalse(body.opened)
    assertEquals(0, h.runCount())
  }

  // ---- refusing the body ----

  @Test
  fun `a body that is not a json object is refused and the delivery stays usable`() {
    val (_, secret) = hook()

    listOf("", "not json", "[]", "\"file\"", "42", "null", """{"file":""").forEach {
      assertEquals(
          WebhookResult.InvalidBody(BodyProblem.NOT_A_JSON_OBJECT),
          receiver.receive("on-upload", secret, "d-1", Body(it).source),
          it,
      )
    }

    assertTrue(firings().isEmpty())
    assertEquals(0, h.runCount())
    assertEquals(
        WebhookResult.Accepted,
        receiver.receive("on-upload", secret, "d-1", Body("""{"file":"a"}""").source),
    )
    assertEquals(1, h.runCount())
  }

  @Test
  fun `a body over the limit is refused after reading no more than the limit plus one byte`() {
    val (_, secret) = hook()
    val big = Body("""{"file":"${"x".repeat(100_000)}"}""")

    assertEquals(
        WebhookResult.InvalidBody(BodyProblem.TOO_LARGE),
        receiver.receive("on-upload", secret, "d-1", big.source),
    )

    assertTrue(big.taken <= maxBody + 1, "took ${big.taken} bytes")
    assertTrue(firings().isEmpty())
  }

  @Test
  fun `a deeply nested body is refused like any other that is not an object`() {
    val (_, secret) = hook()
    val roomy =
        WebhookReceiver(
            rig.store,
            rig.firer,
            rig.telemetry,
            rig.clock,
            h.definitions,
            WebhookSettings(1_000_000),
        )

    val nested = "[".repeat(200_000)
    val deepObject = """{"file":""" + "{\"a\":".repeat(100_000)

    listOf(nested, deepObject).forEach {
      assertEquals(
          WebhookResult.InvalidBody(BodyProblem.NOT_A_JSON_OBJECT),
          roomy.receive("on-upload", secret, "d-1", Body(it).source),
      )
    }
    assertEquals(0, h.runCount())
  }

  @Test
  fun `a body exactly at the limit is read`() {
    val (_, secret) = hook()
    val prefix = """{"file":""""
    val text = prefix + "x".repeat((maxBody - prefix.length - 2).toInt()) + "\"}"
    assertEquals(maxBody, text.length.toLong())

    assertEquals(
        WebhookResult.Accepted,
        receiver.receive("on-upload", secret, "d-1", Body(text).source),
    )
  }

  @Test
  fun `keys outside the list, missing required keys and values that are not text are named`() {
    val (_, secret) = hook(caller = listOf("file", "mode"))

    assertEquals(
        WebhookResult.InvalidParameters(
            listOf(CallerParameterProblem("file", CallerProblemKind.MISSING))
        ),
        receiver.receive("on-upload", secret, "d-1", Body("""{"mode":"slow"}""").source),
    )
    assertEquals(
        WebhookResult.InvalidParameters(
            listOf(CallerParameterProblem("owner", CallerProblemKind.UNDECLARED))
        ),
        receiver.receive("on-upload", secret, "d-1", Body("""{"file":"a","owner":"me"}""").source),
    )
    assertEquals(
        WebhookResult.InvalidParameters(
            listOf(CallerParameterProblem("nope", CallerProblemKind.UNDECLARED))
        ),
        receiver.receive("on-upload", secret, "d-1", Body("""{"file":"a","nope":"1"}""").source),
    )
    assertEquals(
        WebhookResult.InvalidParameters(
            listOf(CallerParameterProblem("file", CallerProblemKind.NOT_TEXT))
        ),
        receiver.receive("on-upload", secret, "d-1", Body("""{"file":42}""").source),
    )
    assertEquals(
        WebhookResult.InvalidParameters(
            listOf(
                CallerParameterProblem("file", CallerProblemKind.MISSING),
                CallerParameterProblem("mode", CallerProblemKind.NOT_TEXT),
                CallerParameterProblem("zzz", CallerProblemKind.UNDECLARED),
            )
        ),
        receiver.receive("on-upload", secret, "d-1", Body("""{"mode":null,"zzz":"1"}""").source),
    )

    assertTrue(firings().isEmpty())
    assertEquals(0, h.runCount())
    assertEquals(
        WebhookResult.Accepted,
        receiver.receive("on-upload", secret, "d-1", Body("""{"file":"a"}""").source),
    )
  }

  // ---- repeats ----

  @Test
  fun `a repeated delivery is accepted again, its body is ignored and no second run is made`() {
    val (_, secret) = hook()
    receiver.receive("on-upload", secret, "d-1", Body("""{"file":"a"}""").source)
    val repeat = Body("this is not even json")

    assertEquals(
        WebhookResult.Accepted,
        receiver.receive("on-upload", secret, "d-1", repeat.source),
    )

    assertFalse(repeat.opened)
    assertEquals(1, h.runCount())
    assertEquals(1, firings().size)
    assertEquals(
        mapOf("trigger=on-upload" to 1L),
        rig.counter("runline.triggers.webhook.duplicates"),
    )
  }

  // ---- telemetry and what must not be seen ----

  @Test
  fun `calls refused for their parameters are told apart in the metrics and the log`() {
    val (_, secret) = hook()

    receiver.receive("on-upload", secret, "d-1", Body("nope").source)
    receiver.receive("on-upload", secret, "d-1", Body("x".repeat(1000)).source)
    receiver.receive("on-upload", secret, "d-1", Body("""{"file":1}""").source)
    receiver.receive("on-upload", "wrong", "d-1", Body("""{"file":1}""").source)

    assertEquals(
        mapOf(
            "cause=body_not_object,reason=invalid_parameters" to 1L,
            "cause=body_too_large,reason=invalid_parameters" to 1L,
            "cause=invalid_parameters,reason=invalid_parameters" to 1L,
            "cause=wrong_secret,reason=unauthorized" to 1L,
        ),
        rejections(),
    )
    assertTrue(
        logs.at(Level.WARN).any { it.contains("invalid_parameters") && it.contains("on-upload") },
        logs.lines.toString(),
    )
  }

  @Test
  fun `neither the values, nor the secret, nor the keys of a body appear in a log line or a metric`() {
    val (_, secret) = hook()
    val value = "very-private-value-8841"
    val key = "sneaky-key-5530"
    receiver.receive(
        "on-upload",
        secret,
        "d-1",
        Body("""{"file":"$value-bad","$key":"$value"}""").source,
    )
    receiver.receive("on-upload", secret, "d-1", Body("""{"file":$value""").source)
    receiver.receive("on-upload", secret, "d-1", Body("""{"file":"$value"}""").source)
    receiver.receive("on-upload", secret, "d-2", Body("""{"file":"$value"}""").source)
    receiver.receive("on-upload", secret, "d-3", Body("""{"file":"$value","$key":1}""").source)

    val everything =
        logs.lines.joinToString("\n") +
            rig.metrics.collectAllMetrics().joinToString("\n") { it.toString() } +
            firings().joinToString("\n") { "${it.reason} ${it.detail}" }
    listOf(value, key, secret).forEach {
      assertFalse(everything.contains(it), "found $it in logs, metrics or the firing record")
    }
    assertTrue(logs.lines.any { it.contains("on-upload") }, "the test must see real log lines")
  }
}
