package dev.lawlan.runline.engine.run

import dev.lawlan.runline.analyzer.Verdict
import dev.lawlan.runline.runner.LoaderStats
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class RunTelemetryTest {
  private val spans = InMemorySpanExporter.create()
  private val reader = InMemoryMetricReader.create()
  private val otel =
      OpenTelemetrySdk.builder()
          .setTracerProvider(
              SdkTracerProvider.builder()
                  .addSpanProcessor(SimpleSpanProcessor.create(spans))
                  .build()
          )
          .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(reader).build())
          .build()
  private val telemetry = RunTelemetry(otel)

  private fun run(
      source: RunSource = RunSource.Manual("alice"),
      id: UUID = UUID.randomUUID(),
  ) =
      RunRecord(
          id,
          "h".repeat(64),
          "alice",
          "p.Nightly",
          "nightly",
          RunState.QUEUED,
          source,
          emptyMap(),
          Instant.now(),
          null,
          null,
          null,
          null,
      )

  private fun SpanData.text(key: String) = attributes.get(AttributeKey.stringKey(key))

  private fun metric(name: String) = reader.collectAllMetrics().firstOrNull { it.name == name }

  private fun counter(name: String): Map<String, Long> =
      metric(name)?.longSumData?.points.orEmpty().associate {
        it.attributes.asMap().entries.joinToString(",") { e -> "${e.key.key}=${e.value}" } to
            it.value
      }

  // ---- trace ----

  @Test
  fun `a run has one trace with its source and a span for each phase it went through`() {
    val run = run(RunSource.Trigger("nightly-cron"))
    telemetry.accepted(run, Verdict.SAFE)
    telemetry.entered(run.id, RunState.INITIALIZING)
    telemetry.entered(run.id, RunState.RUNNING)
    telemetry.ended(run.id, RunState.SUCCEEDED, emptyList())

    val finished = spans.finishedSpanItems
    val root = finished.single { it.name == "runline.run" }
    assertEquals(
        setOf("runline.run.queued", "runline.run.initializing", "runline.run.running"),
        finished.filter { it !== root }.map { it.name }.toSet(),
    )
    assertTrue(finished.all { it.traceId == root.traceId })
    assertTrue(finished.filter { it !== root }.all { it.parentSpanId == root.spanId })
    assertEquals(run.id.toString(), root.text("runline.run.id"))
    assertEquals("nightly", root.text("runline.pipeline"))
    assertEquals("TRIGGER", root.text("runline.run.source.kind"))
    assertEquals("nightly-cron", root.text("runline.run.source.name"))
    assertEquals("SUCCEEDED", root.text("runline.run.state"))
    assertNotEquals(StatusCode.ERROR, root.status.statusCode)
  }

  @Test
  fun `a run that did not succeed is an error trace`() {
    for (state in listOf(RunState.FAILED, RunState.TIMED_OUT, RunState.INTERRUPTED)) {
      val run = run()
      telemetry.accepted(run, Verdict.SAFE)
      telemetry.ended(run.id, state, emptyList())

      val root =
          spans.finishedSpanItems.single {
            it.name == "runline.run" && it.text("runline.run.id") == run.id.toString()
          }
      assertEquals(StatusCode.ERROR, root.status.statusCode, "$state")
    }
  }

  @Test
  fun `a cancelled run is not an error`() {
    val run = run()
    telemetry.accepted(run, Verdict.SAFE)
    telemetry.ended(run.id, RunState.CANCELLED, emptyList())

    val root = spans.finishedSpanItems.single { it.name == "runline.run" }
    assertNotEquals(StatusCode.ERROR, root.status.statusCode)
    assertEquals("CANCELLED", root.text("runline.run.state"))
  }

  @Test
  fun `a run the telemetry has not seen is ignored, as is a state that is not forward`() {
    val unknown = UUID.randomUUID()
    telemetry.entered(unknown, RunState.RUNNING)
    telemetry.ended(unknown, RunState.FAILED, emptyList())
    val run = run()
    telemetry.accepted(run, Verdict.SAFE)
    telemetry.entered(run.id, RunState.RUNNING)
    telemetry.entered(run.id, RunState.INITIALIZING)
    telemetry.ended(run.id, RunState.SUCCEEDED, emptyList())

    val names = spans.finishedSpanItems.map { it.name }
    assertEquals(0, names.count { it == "runline.run.initializing" })
    assertEquals(1, names.count { it == "runline.run.running" })
  }

  // ---- metrics ----

  @Test
  fun `runs created are counted by source and verdict, which gives the share of unsafe runs`() {
    telemetry.accepted(run(), Verdict.SAFE)
    telemetry.accepted(run(), Verdict.UNSAFE)
    telemetry.accepted(run(RunSource.Trigger("t")), Verdict.UNSAFE)

    assertEquals(
        mapOf(
            "source=MANUAL,verdict=SAFE" to 1L,
            "source=MANUAL,verdict=UNSAFE" to 1L,
            "source=TRIGGER,verdict=UNSAFE" to 1L,
        ),
        counter("runline.runs.created"),
    )
  }

  @Test
  fun `refused requests are counted by reason`() {
    telemetry.refused("unsafe_not_allowed", RunSource.Manual("alice"))
    telemetry.refused("unsafe_not_allowed", RunSource.Trigger("t"))
    telemetry.refused("invalid_parameters", RunSource.Manual("alice"))

    assertEquals(
        mapOf("reason=unsafe_not_allowed" to 2L, "reason=invalid_parameters" to 1L),
        counter("runline.runs.refused"),
    )
  }

  @Test
  fun `ended runs are counted by state and threads left behind are counted`() {
    val one = run()
    val two = run()
    telemetry.accepted(one, Verdict.SAFE)
    telemetry.accepted(two, Verdict.SAFE)
    telemetry.ended(one.id, RunState.SUCCEEDED, emptyList())
    telemetry.ended(two.id, RunState.FAILED, listOf("worker-1", "worker-2"))

    assertEquals(
        mapOf("state=SUCCEEDED" to 1L, "state=FAILED" to 1L),
        counter("runline.runs.ended"),
    )
    assertEquals(2L, counter("runline.runs.residual.threads").values.single())
  }

  @Test
  fun `runs that outlived their timeout are counted until they end`() {
    val run = run()
    telemetry.accepted(run, Verdict.SAFE)
    telemetry.entered(run.id, RunState.RUNNING)
    telemetry.entered(run.id, RunState.TIMED_OUT_UNFINISHED)
    assertEquals(1L, counter("runline.runs.timed_out_unfinished").values.single())

    telemetry.ended(run.id, RunState.TIMED_OUT, emptyList())

    assertEquals(0L, counter("runline.runs.timed_out_unfinished").values.single())
  }

  @Test
  fun `running, queued and waiting runs are reported as they are when collected`() {
    var stats = SchedulerStats(queued = 3, waiting = 1, active = 2)
    telemetry.observeScheduler { stats }

    fun gauge(name: String) = metric(name)!!.longGaugeData.points.single().value
    assertEquals(3L, gauge("runline.runs.queued"))
    assertEquals(1L, gauge("runline.runs.waiting"))
    assertEquals(2L, gauge("runline.runs.active"))

    stats = SchedulerStats(0, 0, 5)
    assertEquals(5L, gauge("runline.runs.active"))
  }

  @Test
  fun `class loaders created and reclaimed are reported as they are when collected`() {
    var stats = LoaderStats(created = 4, reclaimed = 3)
    telemetry.observeLoaders { stats }

    fun total(name: String) = metric(name)!!.longSumData.points.single().value
    assertEquals(4L, total("runline.runner.classloaders.created"))
    assertEquals(3L, total("runline.runner.classloaders.reclaimed"))

    stats = LoaderStats(created = 6, reclaimed = 6)
    assertEquals(6L, total("runline.runner.classloaders.reclaimed"))
  }
}
