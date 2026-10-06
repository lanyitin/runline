package dev.lawlan.runline.engine.retention

import dev.lawlan.runline.engine.config.RetentionSettings
import dev.lawlan.runline.engine.run.RunState
import dev.lawlan.runline.engine.support.TriggerRig
import dev.lawlan.runline.engine.trigger.*
import io.opentelemetry.api.OpenTelemetry
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

/**
 * The delivery dedup window (ADR-005): the webhook firing record is what recognises a repeated
 * delivery identifier, and the clean-up is what ends the recognition. Real webhook receiver, real
 * Runner and PostgreSQL; the clock of triggers and of the clean-up is one the test moves.
 */
class RetentionDedupWindowTest {
  private val rig = TriggerRig()
  private val h = rig.harness
  private val receiver = WebhookReceiver(rig.store, rig.firer, rig.telemetry, rig.clock)
  private val window = Duration.ofDays(7)

  private fun cleaner(settings: RetentionSettings = settings()) =
      RetentionCleaner(
          PostgresRetentionStore(h.dataSource),
          settings,
          rig.clock,
          RetentionTelemetry(OpenTelemetry.noop()),
      )

  private fun settings(
      run: Duration = Duration.ofDays(30),
      webhook: Duration = window,
  ) =
      RetentionSettings(
          run,
          run,
          webhook,
          Duration.ofDays(30),
          Duration.ofHours(1),
          1000,
      )

  @AfterTest fun close() = rig.close()

  private fun webhook(): String = rig.webhook("on-push", h.upload("calm"), "calm").second

  private fun firings() = rig.store.firings(rig.store.find("on-push")!!.id, 100)

  @Test
  fun `a delivery repeated inside the window makes one run, and after the window it makes another`() {
    val secret = webhook()
    assertEquals(WebhookResult.Accepted, receiver.receive("on-push", secret, "d-1"))
    h.awaitEnd(checkNotNull(firings().single().runId))
    assertEquals(1, h.runCount())

    rig.clock.advance(Duration.ofDays(6).plusHours(23))
    cleaner().clean()
    assertEquals(WebhookResult.Accepted, receiver.receive("on-push", secret, "d-1"))
    assertEquals(1, h.runCount(), "inside the window the repeat creates no run")

    rig.clock.advance(Duration.ofHours(2))
    // The earlier repeat did not renew the window: it ends seven days after the first delivery.
    val report = cleaner().clean()
    assertEquals(1, report.webhookFirings)
    assertEquals(WebhookResult.Accepted, receiver.receive("on-push", secret, "d-1"))
    assertEquals(2, h.runCount(), "after the window the same identifier is a new delivery")
  }

  @Test
  fun `the window ends only after its last moment`() {
    val secret = webhook()
    receiver.receive("on-push", secret, "d-1")

    rig.clock.advance(window)
    assertEquals(0, cleaner().clean().webhookFirings, "exactly at the window it is still a repeat")
    receiver.receive("on-push", secret, "d-1")
    assertEquals(1, h.runCount())

    rig.clock.advance(Duration.ofSeconds(1))
    assertEquals(1, cleaner().clean().webhookFirings)
    receiver.receive("on-push", secret, "d-1")
    assertEquals(2, h.runCount())
  }

  @Test
  fun `a delivery that is not yet settled is not forgotten however long it takes`() {
    val (trigger, secret) = rig.webhook("slow", h.upload("calm"), "calm")
    // A claim that is not settled: the Engine is in the middle of it.
    val claim = checkNotNull(rig.store.claimDelivery(trigger.id, "d-1", rig.clock.instant()))
    rig.clock.advance(Duration.ofDays(100))

    assertEquals(0, cleaner().clean().webhookFirings)

    assertEquals(WebhookResult.Accepted, receiver.receive("slow", secret, "d-1"))
    assertEquals(0, h.runCount(), "the delivery is still claimed: no second run")
    assertEquals(claim, rig.store.firings(trigger.id, 10).single().id)
  }

  @Test
  fun `removing the firing leaves the run it created`() {
    val secret = webhook()
    receiver.receive("on-push", secret, "d-1")
    val runId = checkNotNull(firings().single().runId)
    h.awaitEnd(runId)

    rig.clock.advance(window.plusSeconds(1))
    cleaner().clean()

    assertEquals(emptyList(), firings())
    assertEquals(RunState.SUCCEEDED, h.state(runId))
  }

  @Test
  fun `removing the run keeps the firing, without the run, and the window goes on`() {
    val secret = webhook()
    receiver.receive("on-push", secret, "d-1")
    val runId = checkNotNull(firings().single().runId)
    h.awaitEnd(runId)

    // Runs are kept an hour and webhook firings long; the run goes first.
    rig.clock.advance(Duration.ofDays(400))
    val report =
        cleaner(settings(run = Duration.ofHours(1), webhook = Duration.ofDays(1000))).clean()

    assertEquals(1, report.runs)
    assertEquals(0, h.runCount())
    val firing = firings().single()
    assertEquals(FiringOutcome.RUN_CREATED, firing.outcome)
    assertNull(firing.runId)
    assertEquals(WebhookResult.Accepted, receiver.receive("on-push", secret, "d-1"))
    assertEquals(0, h.runCount(), "the identifier is still a repeat, although its run is gone")
  }

  @Test
  fun `a delivery sent many times at once inside the window makes one run, while the clean-up runs`() {
    val secret = webhook()
    val callers = 16
    val pool = Executors.newCachedThreadPool()
    val start = CountDownLatch(1)
    val done = AtomicBoolean(false)
    try {
      val cleaning = pool.submit {
        start.await()
        val cleaner = cleaner()
        // Inside the window the clean-up has nothing to remove; it must not remove the claim.
        while (!done.get()) cleaner.clean()
      }
      val results =
          (1..callers).map {
            pool.submit<WebhookResult> {
              start.await()
              receiver.receive("on-push", secret, "racing")
            }
          }
      start.countDown()
      val outcomes = results.map { it.get(60, TimeUnit.SECONDS) }
      done.set(true)
      cleaning.get(60, TimeUnit.SECONDS)

      assertEquals(List(callers) { WebhookResult.Accepted }, outcomes)
      assertEquals(1, h.runCount())
      assertEquals(1, firings().size)
    } finally {
      done.set(true)
      pool.shutdownNow()
    }
  }

  @Test
  fun `a delivery sent many times at once after the window makes exactly one new run`() {
    val secret = webhook()
    receiver.receive("on-push", secret, "racing")
    assertEquals(1, h.runCount())
    rig.clock.advance(window.plusSeconds(1))
    cleaner().clean()
    val callers = 16
    val pool = Executors.newCachedThreadPool()
    val start = CountDownLatch(1)
    try {
      val results =
          (1..callers).map {
            pool.submit<WebhookResult> {
              start.await()
              receiver.receive("on-push", secret, "racing")
            }
          }
      start.countDown()
      val outcomes = results.map { it.get(60, TimeUnit.SECONDS) }

      assertEquals(List(callers) { WebhookResult.Accepted }, outcomes)
      assertEquals(2, h.runCount(), "one run for the first delivery and one for the second")
      assertEquals(1, firings().size)
    } finally {
      pool.shutdownNow()
    }
  }
}
