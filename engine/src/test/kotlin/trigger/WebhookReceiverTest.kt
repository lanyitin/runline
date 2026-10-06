package dev.lawlan.runline.engine.trigger

import ch.qos.logback.classic.Level
import dev.lawlan.runline.engine.run.RunSource
import dev.lawlan.runline.engine.run.RunState
import dev.lawlan.runline.engine.support.CapturedLogs
import dev.lawlan.runline.engine.support.TriggerRig
import dev.lawlan.runline.engine.support.getWithin
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.*

class WebhookReceiverTest {
  private val rig = TriggerRig()
  private val h = rig.harness
  private val logs = CapturedLogs()
  private val receiver = WebhookReceiver(rig.store, rig.firer, rig.telemetry, rig.clock)
  private val unsafeBody = "java.nio.file.Files.exists(java.nio.file.Path.of(\"x\"));"

  @AfterTest
  fun close() {
    logs.close()
    rig.close()
  }

  private fun firings(name: String) = rig.store.firings(rig.store.find(name)!!.id, 1000)

  private fun rejections() = rig.counter("runline.triggers.webhook.rejected")

  // ---- accepting ----

  @Test
  fun `a call with the secret and a delivery identifier is accepted and creates a run`() {
    val (_, secret) = rig.webhook("on-push", h.upload("calm"), "calm")

    assertEquals(WebhookResult.Accepted, receiver.receive("on-push", secret, "delivery-1"))

    val firing = firings("on-push").single()
    assertEquals("delivery-1", firing.deliveryId)
    assertEquals(FiringOutcome.RUN_CREATED, firing.outcome)
    val run = h.await(checkNotNull(firing.runId), RunState.SUCCEEDED)
    assertEquals(RunSource.Trigger("on-push"), run.source)
  }

  @Test
  fun `the run gets the parameters of the trigger`() {
    val hash =
        h.upload(
            "calm",
            declaration =
                dev.lawlan.runline.engine.support.RunHarness.DEFAULT_DECLARATION +
                    ", parameters = {@Param(name = \"who\")}",
        )
    val (_, secret) = rig.webhook("on-push", hash, "calm", mapOf("who" to "world"))

    receiver.receive("on-push", secret, "d-1")

    val run = h.await(checkNotNull(firings("on-push").single().runId), RunState.SUCCEEDED)
    assertEquals(mapOf("who" to "world"), run.parameters)
  }

  @Test
  fun `different deliveries each create a run`() {
    val (_, secret) = rig.webhook("on-push", h.upload("calm"), "calm")

    listOf("d-1", "d-2", "d-3").forEach {
      assertEquals(WebhookResult.Accepted, receiver.receive("on-push", secret, it))
    }

    assertEquals(3, h.runCount())
  }

  @Test
  fun `a refused creation is still accepted and only recorded for the administrator`() {
    val (_, secret) = rig.webhook("on-push", h.upload("risky", unsafeBody), "risky")

    assertEquals(WebhookResult.Accepted, receiver.receive("on-push", secret, "d-1"))

    assertEquals(0, h.runCount())
    val firing = firings("on-push").single()
    assertEquals(FiringOutcome.REFUSED, firing.outcome)
    assertEquals("unsafe_not_allowed", firing.reason)
    assertTrue(rig.store.find("on-push")!!.enabled)
    assertEquals(WebhookResult.Accepted, receiver.receive("on-push", secret, "d-2"))
    assertEquals(2, firings("on-push").size)
  }

  // ---- authentication ----

  @Test
  fun `a wrong or missing secret, an unknown name and a trigger that is not a webhook are all unauthorized`() {
    val hash = h.upload("calm")
    val (_, secret) = rig.webhook("on-push", hash, "calm")
    rig.cron("a-cron", hash, "calm")

    assertEquals(WebhookResult.Unauthorized, receiver.receive("on-push", "wrong", "d-1"))
    assertEquals(WebhookResult.Unauthorized, receiver.receive("on-push", "", "d-1"))
    assertEquals(WebhookResult.Unauthorized, receiver.receive("on-push", null, "d-1"))
    assertEquals(WebhookResult.Unauthorized, receiver.receive("nobody", secret, "d-1"))
    assertEquals(WebhookResult.Unauthorized, receiver.receive("a-cron", secret, "d-1"))

    assertEquals(0, h.runCount())
    assertTrue(firings("on-push").isEmpty())
    assertEquals(
        mapOf(
            "cause=no_secret,reason=unauthorized" to 2L,
            "cause=wrong_secret,reason=unauthorized" to 1L,
            "cause=unknown_trigger,reason=unauthorized" to 2L,
        ),
        rejections(),
    )
  }

  @Test
  fun `a disabled trigger answers like a failed authentication and creates no run`() {
    val (_, secret) = rig.webhook("on-push", h.upload("calm"), "calm", enabled = false)

    assertEquals(WebhookResult.Unauthorized, receiver.receive("on-push", secret, "d-1"))

    assertEquals(0, h.runCount())
    assertTrue(firings("on-push").isEmpty())
    assertEquals(mapOf("cause=disabled,reason=unauthorized" to 1L), rejections())

    rig.admin.update("on-push", UpdateTrigger(enabled = true), rig.root)
    assertEquals(WebhookResult.Accepted, receiver.receive("on-push", secret, "d-1"))
  }

  @Test
  fun `after a rotation the old secret stops working at once and the new one works`() {
    val (_, old) = rig.webhook("on-push", h.upload("calm"), "calm")
    val new = (rig.admin.rotateSecret("on-push", rig.root) as RotateSecretResult.Rotated).secret

    assertEquals(WebhookResult.Unauthorized, receiver.receive("on-push", old, "d-1"))
    assertEquals(WebhookResult.Accepted, receiver.receive("on-push", new, "d-1"))
    assertEquals(1, h.runCount())
  }

  @Test
  fun `the secret of one trigger does not open another`() {
    val hash = h.upload("calm")
    val (_, one) = rig.webhook("one", hash, "calm")
    rig.webhook("two", hash, "calm")

    assertEquals(WebhookResult.Unauthorized, receiver.receive("two", one, "d-1"))
  }

  // ---- delivery identifier ----

  @Test
  fun `an authenticated call needs a well formed delivery identifier`() {
    val (_, secret) = rig.webhook("on-push", h.upload("calm"), "calm")

    listOf(null, "", "has space", "tab\there", "x".repeat(201), "café").forEach {
      assertEquals(WebhookResult.InvalidDelivery, receiver.receive("on-push", secret, it), "$it")
    }
    assertEquals(WebhookResult.Accepted, receiver.receive("on-push", secret, "x".repeat(200)))
    assertEquals(0L + 1, h.runCount())
    assertEquals(
        mapOf(
            "cause=malformed,reason=invalid_delivery_id" to 5L,
            "cause=missing,reason=invalid_delivery_id" to 1L,
        ),
        rejections(),
    )
  }

  @Test
  fun `authentication is decided before the delivery identifier`() {
    rig.webhook("on-push", h.upload("calm"), "calm")

    assertEquals(WebhookResult.Unauthorized, receiver.receive("on-push", "wrong", null))
    assertEquals(WebhookResult.Unauthorized, receiver.receive("nobody", "wrong", null))
  }

  // ---- repeated deliveries ----

  @Test
  fun `a repeated delivery is accepted again without another run`() {
    val (_, secret) = rig.webhook("on-push", h.upload("calm"), "calm")

    assertEquals(WebhookResult.Accepted, receiver.receive("on-push", secret, "d-1"))
    assertEquals(WebhookResult.Accepted, receiver.receive("on-push", secret, "d-1"))
    assertEquals(WebhookResult.Accepted, receiver.receive("on-push", secret, "d-1"))

    assertEquals(1, h.runCount())
    assertEquals(1, firings("on-push").size)
    assertEquals(mapOf("trigger=on-push" to 2L), rig.counter("runline.triggers.webhook.duplicates"))
    assertTrue(
        logs.at(Level.INFO).any {
          it.contains("on-push") && it.contains("d-1") && it.contains("repeat")
        },
        logs.lines.toString(),
    )
  }

  @Test
  fun `a repeat of a refused delivery is accepted too and does not try again`() {
    val (_, secret) = rig.webhook("on-push", h.upload("risky", unsafeBody), "risky")
    receiver.receive("on-push", secret, "d-1")
    h.definitions.setUnsafeExecution(
        rig.store.find("on-push")!!.contentHash,
        "risky",
        true,
        "root",
        java.time.Instant.now(),
    )

    assertEquals(WebhookResult.Accepted, receiver.receive("on-push", secret, "d-1"))

    assertEquals(0, h.runCount())
  }

  @Test
  fun `the same delivery identifier on another trigger is a different delivery`() {
    val hash = h.upload("calm")
    val (_, one) = rig.webhook("one", hash, "calm")
    val (_, two) = rig.webhook("two", hash, "calm")

    receiver.receive("one", one, "same")
    receiver.receive("two", two, "same")

    assertEquals(2, h.runCount())
  }

  @Test
  fun `a delivery sent many times at once creates exactly one run and all are accepted`() {
    val (_, secret) = rig.webhook("on-push", h.upload("calm"), "calm")
    val callers = 24
    val pool = Executors.newFixedThreadPool(callers)
    val start = CountDownLatch(1)

    val results =
        (1..callers).map {
          pool.submit<WebhookResult> {
            start.await()
            receiver.receive("on-push", secret, "racing")
          }
        }
    start.countDown()
    val outcomes = results.map { it.getWithin("a delivery of the webhook") }
    pool.shutdown()

    assertEquals(List(callers) { WebhookResult.Accepted }, outcomes)
    assertEquals(1, h.runCount())
    assertEquals(1, firings("on-push").size)
  }

  // ---- errors ----

  @Test
  fun `an unexpected failure is reported and the same delivery can be sent again`() {
    val (_, secret) = rig.webhook("on-push", h.upload("calm"), "calm")
    execute("ALTER TABLE run ADD CONSTRAINT no_new_runs CHECK (false) NOT VALID")

    assertEquals(WebhookResult.Failed, receiver.receive("on-push", secret, "d-1"))
    assertEquals(0, h.runCount())

    execute("ALTER TABLE run DROP CONSTRAINT no_new_runs")
    assertEquals(WebhookResult.Accepted, receiver.receive("on-push", secret, "d-1"))
    assertEquals(1, h.runCount())
    assertEquals(1, firings("on-push").size)
  }

  // ---- secrets stay out of logs and metrics ----

  @Test
  fun `neither the secret nor its hash appears in any log line or metric`() {
    val (trigger, secret) = rig.webhook("on-push", h.upload("calm"), "calm")
    val hash = rig.store.webhookCredential("on-push")!!.secretHash
    val wrong = "not-the-secret-0123456789abcdefghijklmnopqrstuvwxyz"
    receiver.receive("on-push", secret, "d-1")
    receiver.receive("on-push", secret, "d-1")
    receiver.receive("on-push", wrong, "d-2")
    receiver.receive("on-push", secret, null)
    val rotated = (rig.admin.rotateSecret("on-push", rig.root) as RotateSecretResult.Rotated).secret
    receiver.receive("on-push", secret, "d-3")
    receiver.receive("on-push", rotated, "d-3")
    rig.admin.update("on-push", UpdateTrigger(enabled = false), rig.root)
    receiver.receive("on-push", rotated, "d-4")

    val everything =
        logs.lines.joinToString("\n") +
            rig.metrics.collectAllMetrics().joinToString("\n") { it.toString() }
    listOf(secret, hash, wrong, rotated, rig.store.webhookCredential("on-push")!!.secretHash)
        .forEach { assertFalse(everything.contains(it), "found a secret in logs or metrics") }
    assertTrue(logs.lines.any { it.contains("on-push") }, "the test must see real log lines")
    assertEquals(trigger.name, "on-push")
  }

  private fun execute(sql: String) =
      h.dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
}
