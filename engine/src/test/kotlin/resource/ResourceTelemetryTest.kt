package dev.lawlan.runline.engine.resource

import ch.qos.logback.classic.Logger
import dev.lawlan.runline.engine.auth.ApiIdentity
import dev.lawlan.runline.engine.auth.Role
import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.run.GateDecision
import dev.lawlan.runline.engine.run.PendingRun
import dev.lawlan.runline.engine.support.MutableClock
import dev.lawlan.runline.engine.support.SnapshotListAppender
import dev.lawlan.runline.engine.support.migratedDatabase
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import java.time.Duration
import java.util.UUID
import kotlin.test.*
import org.slf4j.LoggerFactory

/** The log lines and metrics that waiting, acquiring, releasing and forced release leave behind. */
class ResourceTelemetryTest {
  private val reader = InMemoryMetricReader.create()
  private val otel =
      OpenTelemetrySdk.builder()
          .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(reader).build())
          .build()
  private val store = PostgresResourceStore(dataSourceOf(migratedDatabase()))
  private val clock = MutableClock()
  private val admin = ResourceAdmin(store, clock) {}
  private val root = ApiIdentity("root", Role.ADMIN)
  private val coordinator =
      ResourceCoordinator(
          ResourceAvailability(store),
          clock,
          Duration.ofHours(1),
          ResourceTelemetry(otel),
      )
  private val logs = SnapshotListAppender().also { it.start() }
  private val logger = LoggerFactory.getLogger(ResourceCoordinator::class.java) as Logger

  init {
    logger.addAppender(logs)
    admin.create("a", 1, root)
  }

  @AfterTest
  fun tearDown() {
    logger.detachAppender(logs)
    coordinator.close()
  }

  private fun run(pipeline: String = "p") = PendingRun(UUID.randomUUID(), pipeline, listOf("a"))

  private fun metric(name: String): MetricData? =
      reader.collectAllMetrics().firstOrNull { it.name == name }

  private fun histogramSum(name: String, vararg labels: Pair<String, String>): Double? =
      metric(name)
          ?.histogramData
          ?.points
          ?.firstOrNull { p ->
            labels.all { (k, v) ->
              p.attributes.asMap().entries.any { it.key.key == k && it.value == v }
            }
          }
          ?.sum

  private fun gauge(name: String, resource: String): Long? =
      metric(name)
          ?.longGaugeData
          ?.points
          ?.firstOrNull { p ->
            p.attributes.asMap().entries.any { it.key.key == "resource" && it.value == resource }
          }
          ?.value

  private fun lines() = logs.snapshot().map { it.formattedMessage }

  @Test
  fun `waiting time is measured from when a run began to wait until it got the resource`() {
    val holder = run()
    val waiter = run()
    coordinator.tryAcquire(holder)
    coordinator.tryAcquire(waiter)
    clock.advance(Duration.ofSeconds(7))
    coordinator.release(holder.id)

    coordinator.tryAcquire(waiter)

    assertEquals(
        7.0,
        histogramSum(
            "runline.resources.wait.duration",
            "resource" to "a",
            "outcome" to "ACQUIRED",
        )!!,
        0.001,
    )
  }

  @Test
  fun `a run that gets the resource at once is counted as having waited no time`() {
    coordinator.tryAcquire(run())

    assertEquals(0.0, histogramSum("runline.resources.wait.duration", "outcome" to "ACQUIRED"))
  }

  @Test
  fun `holding time is measured until the run ends`() {
    val holder = run()
    coordinator.tryAcquire(holder)
    clock.advance(Duration.ofSeconds(42))

    coordinator.release(holder.id)

    assertEquals(
        42.0,
        histogramSum("runline.resources.hold.duration", "resource" to "a", "how" to "released"),
    )
  }

  @Test
  fun `a forced release records the holding time as forced`() {
    val holder = run()
    coordinator.tryAcquire(holder)
    clock.advance(Duration.ofSeconds(9))

    coordinator.forceRelease("a", holder.id, root)

    assertEquals(9.0, histogramSum("runline.resources.hold.duration", "how" to "forced"))
    assertNull(histogramSum("runline.resources.hold.duration", "how" to "released"))
  }

  @Test
  fun `a wait that is cancelled is recorded with that outcome`() {
    val holder = run()
    val waiter = run()
    coordinator.tryAcquire(holder)
    coordinator.tryAcquire(waiter)
    clock.advance(Duration.ofSeconds(3))

    coordinator.release(waiter.id)

    assertEquals(3.0, histogramSum("runline.resources.wait.duration", "outcome" to "CANCELLED"))
  }

  @Test
  fun `the queue length and the holders are reported per resource, also back at zero`() {
    val holder = run()
    val waiter = run()
    coordinator.tryAcquire(holder)
    coordinator.tryAcquire(waiter)

    assertEquals(1, gauge("runline.resources.queue.length", "a"))
    assertEquals(1, gauge("runline.resources.holders", "a"))

    coordinator.release(waiter.id)
    coordinator.release(holder.id)

    assertEquals(0, gauge("runline.resources.queue.length", "a"))
    assertEquals(0, gauge("runline.resources.holders", "a"))
  }

  @Test
  fun `waiting, acquiring and releasing each leave a log line naming the run and the resource`() {
    val holder = run("holder-pipeline")
    val waiter = run("waiter-pipeline")

    coordinator.tryAcquire(holder)
    coordinator.tryAcquire(waiter)
    coordinator.release(holder.id)
    coordinator.tryAcquire(waiter)

    assertTrue(
        lines().any { it.contains("${holder.id}") && it.contains("acquired") && it.contains("a") }
    )
    assertTrue(lines().any { it.contains("${waiter.id}") && it.contains("waits for") })
    assertTrue(lines().any { it.contains("${holder.id}") && it.contains("released") })
    assertTrue(lines().any { it.contains("${waiter.id}") && it.contains("acquired") })
  }

  @Test
  fun `a forced release is logged as a warning with who did it`() {
    val holder = run("stuck-pipeline")
    coordinator.tryAcquire(holder)

    coordinator.forceRelease("a", holder.id, ApiIdentity("ops", Role.ADMIN))

    val event = logs.snapshot().single { it.formattedMessage.contains("by force") }
    assertEquals(ch.qos.logback.classic.Level.WARN, event.level)
    assertTrue(event.formattedMessage.contains("ops"))
    assertTrue(event.formattedMessage.contains("${holder.id}"))
    assertTrue(event.formattedMessage.contains("stuck-pipeline"))
    assertEquals(
        1L,
        metric("runline.resources.force_released")?.longSumData?.points?.single()?.value,
    )
  }

  @Test
  fun `the decision to refuse a run is not mistaken for a grant in the metrics`() {
    val r = PendingRun(UUID.randomUUID(), "p", listOf("ghost"))

    assertIs<GateDecision.Refused>(coordinator.tryAcquire(r))

    assertEquals(
        0.0,
        histogramSum(
            "runline.resources.wait.duration",
            "resource" to "ghost",
            "outcome" to "REFUSED",
        ),
    )
    assertNull(histogramSum("runline.resources.wait.duration", "outcome" to "ACQUIRED"))
  }
}
