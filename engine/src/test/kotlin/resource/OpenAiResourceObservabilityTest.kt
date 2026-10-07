package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.accessors.fake.FakeOpenAiServer
import dev.lawlan.runline.engine.run.RunState
import dev.lawlan.runline.engine.support.CapturedLogs
import dev.lawlan.runline.engine.support.RunHarness
import dev.lawlan.runline.engine.support.RunHarness.Companion.usingTyped
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import java.nio.file.Files
import java.time.Duration
import java.util.UUID
import kotlin.test.*

/**
 * What the Engine records of the calls on an `openai-compatible` resource (ADR-019 decision 10,
 * WI-46): the metrics with their fixed labels, a span of the run's trace per request with the time
 * it waited and the time to the first byte, the run's log and the Engine's log, and the use an
 * administrator sees. Nothing of a request or an answer is in any of them.
 */
class OpenAiResourceObservabilityTest {
  private val server = FakeOpenAiServer()
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
  private val usage = OpenAiUsage()
  private val h =
      RunHarness(
          maxConcurrent = 2,
          resourceWaitTimeout = Duration.ofHours(1),
          openTelemetry = otel,
          openAiObserver = OpenAiTelemetry(otel, usage),
          allowList =
              listOf(
                      "java.lang",
                      "java.util",
                      "java.io",
                      "java.time",
                      "kotlin",
                      "org.jetbrains.annotations",
                  )
                  .map { dev.lawlan.runline.analyzer.AllowListEntry(it) },
      )

  @AfterTest
  fun close() {
    h.close()
    server.close()
    logs.close()
  }

  private val promptMarker = "prompt-marker-xyz-123"
  private val modelMarker = "model-marker-abc-456"

  private fun define(extra: String = "") =
      h.defineOpenAi(
          "lemon",
          """{"baseUrl":"${server.baseUrl}"${if (extra.isEmpty()) "" else ",$extra"}}""",
      )

  private fun pipeline(name: String, body: String = promptBody()): String =
      h.upload(name, body, declaration = usingTyped("lemon" to "openai-compatible"))

  private fun promptBody(
      endpoint: String = "chat.completions",
      timeouts: String = "new OpenAiTimeouts()",
  ) =
      """
      OpenAiAccessor lemon = context.getAccessors().openAiCompatible("lemon");
      try {
        lemon.call(new OpenAiRequest("$endpoint", "{\"model\":\"$modelMarker\",\"messages\":[{\"role\":\"user\",\"content\":\"$promptMarker\"}]}",
            java.util.Collections.<String,String>emptyMap(), java.util.Collections.<String,String>emptyMap(), $timeouts));
      } catch (ResourceAccessException e) {
        context.getFiles().writeText(FileScope.PIPELINE_SHARED, "failure", e.getFailure().name());
      }
      """
          .trimIndent()

  private fun ran(name: String, body: String = promptBody()): UUID {
    val id = h.start(pipeline(name, body), name)
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(id).state)
    return id
  }

  private fun metric(name: String): MetricData? =
      metrics.collectAllMetrics().firstOrNull { it.name == name }

  private fun labels(point: io.opentelemetry.sdk.metrics.data.PointData) =
      point.attributes.asMap().mapKeys { it.key.key }.mapValues { "${it.value}" }

  private fun counter(name: String, vararg match: Pair<String, String>): Long =
      metric(name)
          ?.longSumData
          ?.points
          ?.filter { point -> match.all { (k, v) -> labels(point)[k] == v } }
          ?.map { it.value }
          ?.sum() ?: 0L

  private fun histogramCount(name: String, vararg match: Pair<String, String>): Long =
      metric(name)
          ?.histogramData
          ?.points
          ?.filter { point -> match.all { (k, v) -> labels(point)[k] == v } }
          ?.map { it.count }
          ?.sum() ?: 0L

  private fun openAiSpans(): List<SpanData> =
      spans.finishedSpanItems.filter { it.name == "runline.resource.openai.call" }

  private fun attribute(span: SpanData, key: String): String? =
      span.attributes.get(AttributeKey.stringKey("runline.resource.$key"))

  // ---- metrics ----

  @Test
  fun `a successful call is counted with its endpoint and outcome, timed, and its tokens are counted`() {
    define()
    server.responseDelayMillis = 200

    ran("caller")

    val base = arrayOf("resource" to "lemon", "type" to "openai-compatible")
    assertEquals(
        1,
        counter(
            "runline.resources.openai.requests",
            *base,
            "endpoint" to "chat.completions",
            "outcome" to "ok",
        ),
    )
    assertEquals(
        1,
        histogramCount("runline.resources.openai.request.duration", *base, "outcome" to "ok"),
    )
    assertEquals(1, histogramCount("runline.resources.openai.quota_wait.duration", *base))
    assertEquals(
        1,
        histogramCount(
            "runline.resources.openai.generation.duration",
            *base,
            "endpoint" to "chat.completions",
        ),
    )
    assertEquals(3, counter("runline.resources.openai.tokens", *base, "kind" to "prompt"))
    assertEquals(5, counter("runline.resources.openai.tokens", *base, "kind" to "completion"))
    val duration =
        metric("runline.resources.openai.request.duration")!!.histogramData.points.single().sum
    assertTrue(duration >= 0.15, "the request took $duration s")
  }

  @Test
  fun `the labels are a fixed set of names and values from fixed sets, never what a pipeline wrote`() {
    define()
    ran("caller")
    ran("caller-2")

    val points =
        metrics
            .collectAllMetrics()
            .filter { it.name.startsWith("runline.resources.openai.") }
            .flatMap { metric ->
              (metric.longSumData.points +
                      metric.histogramData.points +
                      metric.longGaugeData.points)
                  .map { metric.name to labels(it) }
            }
    assertTrue(points.isNotEmpty())
    val allowedKeys = setOf("resource", "type", "endpoint", "outcome", "kind")
    for ((name, labelSet) in points) {
      assertTrue(allowedKeys.containsAll(labelSet.keys), "$name: ${labelSet.keys}")
      assertTrue(
          labelSet.values.none {
            it.contains(promptMarker) || it.contains(modelMarker) || it.contains("http")
          },
          "$name: $labelSet",
      )
    }
  }

  @Test
  fun `each kind of outcome and of timeout is counted under its own label`() {
    define("\"timeouts\":{\"quotaWaitMs\":60000}")
    server.script = { _, response ->
      response.json(429, "{}")
      true
    }
    ran("limited")
    server.script = null
    server.responseDelayMillis = 1500
    ran(
        "impatient",
        promptBody(
            timeouts =
                "new OpenAiTimeouts(null, java.time.Duration.ofMillis(200), null, null, null)"
        ),
    )

    val base = arrayOf("resource" to "lemon", "type" to "openai-compatible")
    assertEquals(
        1,
        counter("runline.resources.openai.requests", *base, "outcome" to "rate_limited"),
    )
    assertEquals(
        1,
        counter("runline.resources.openai.requests", *base, "outcome" to "first_byte_timeout"),
    )
    assertEquals(1, counter("runline.resources.openai.timeouts", *base, "kind" to "first_byte"))
    assertEquals(0, counter("runline.resources.openai.timeouts", *base, "kind" to "idle"))
  }

  @Test
  fun `a call that waits too long for its share is a quota wait timeout and is timed`() {
    define("\"timeouts\":{\"quotaWaitMs\":300}")
    server.responseDelayMillis = 1500
    val body =
        """
        final OpenAiAccessor lemon = context.getAccessors().openAiCompatible("lemon");
        Thread t = new Thread(() -> {
          try { lemon.call(new OpenAiRequest("chat.completions", "{}")); } catch (ResourceAccessException e) { }
        });
        t.start();
        try { Thread.sleep(400); } catch (InterruptedException e) { throw new RuntimeException(e); }
        try { lemon.call(new OpenAiRequest("chat.completions", "{}")); }
        catch (ResourceAccessException e) { context.getFiles().writeText(FileScope.PIPELINE_SHARED, "failure", e.getFailure().name()); }
        try { t.join(); } catch (InterruptedException e) { throw new RuntimeException(e); }
        """
            .trimIndent()

    ran("waiter", body)

    assertEquals("QUOTA_WAIT_TIMEOUT", Files.readString(h.shared("waiter", "failure")))
    val base = arrayOf("resource" to "lemon", "type" to "openai-compatible")
    assertEquals(1, counter("runline.resources.openai.timeouts", *base, "kind" to "quota_wait"))
    assertEquals(
        1,
        counter("runline.resources.openai.requests", *base, "outcome" to "quota_wait_timeout"),
    )
    assertEquals(2, histogramCount("runline.resources.openai.quota_wait.duration", *base))
  }

  // ---- use ----

  @Test
  fun `the requests in flight are counted while they are, per resource, and are none afterwards`() {
    define()
    server.script = { _, response ->
      response.hang()
      true
    }
    val id = h.start(pipeline("caller"), "caller")
    val deadline = System.nanoTime() + 15_000_000_000
    while (usage.inFlight("lemon") != 1) {
      check(System.nanoTime() < deadline) { "no request in flight" }
      Thread.sleep(10)
    }

    assertEquals(
        1,
        metric("runline.resources.openai.requests.in_flight")!!.longSumData.points.single().value,
    )
    h.service.cancel(id, dev.lawlan.runline.engine.artifact.Visibility.All)
    h.awaitEnd(id)

    assertEquals(0, usage.inFlight("lemon"))
    assertEquals(
        0,
        metric("runline.resources.openai.requests.in_flight")!!.longSumData.points.single().value,
    )
  }

  // ---- traces ----

  @Test
  fun `every request is a span of the run's trace with the time it waited and the time to the first byte`() {
    define()
    server.responseDelayMillis = 250

    ran("caller")

    val root = spans.finishedSpanItems.single { it.name == "runline.run" }
    val span = openAiSpans().single()
    assertEquals(root.spanId, span.parentSpanId)
    assertEquals("lemon", attribute(span, "name"))
    assertEquals("openai-compatible", attribute(span, "type"))
    assertEquals("openai.call", attribute(span, "operation"))
    assertEquals("chat.completions", attribute(span, "openai.endpoint"))
    assertEquals("ok", attribute(span, "openai.outcome"))
    assertTrue(
        span.attributes.get(AttributeKey.longKey("runline.resource.openai.first_byte_ms"))!! >= 200
    )
    assertNotNull(
        span.attributes.get(AttributeKey.longKey("runline.resource.openai.quota_wait_ms"))
    )
  }

  @Test
  fun `a failed request marks its span and says the category`() {
    define()
    server.script = { _, response ->
      response.json(503, "{}")
      true
    }

    ran("caller")

    val span = openAiSpans().single()
    assertEquals("server_error", attribute(span, "openai.outcome"))
    assertEquals(io.opentelemetry.api.trace.StatusCode.ERROR, span.status.statusCode)
  }

  // ---- logs and the rest ----

  @Test
  fun `nothing of a request or of an answer, no address and no model, is in a span, a log or a metric`() {
    define()
    server.script = { _, response ->
      response.json(200, """{"choices":[{"message":{"content":"answer-marker-qrs-789"}}]}""")
      true
    }

    val id = ran("caller")

    val everything = buildString {
      spans.finishedSpanItems.forEach { append(it.toString()).append(it.events.toString()) }
      append(logs.lines.joinToString("\n"))
      append(h.runStore.read(id, 0, 1000).joinToString("\n") { it.line })
      metrics.collectAllMetrics().forEach { append(it.toString()) }
    }
    assertFalse(everything.contains(promptMarker))
    assertFalse(everything.contains(modelMarker))
    assertFalse(everything.contains("answer-marker-qrs-789"))
    assertFalse(everything.contains(server.baseUrl))
    assertFalse(everything.contains("127.0.0.1:${server.port}"))
  }

  @Test
  fun `the run's log says the category of a failure and the Engine's log says the same without the request`() {
    define()
    server.script = { _, response ->
      response.json(429, "{}")
      true
    }

    val id = ran("caller")

    val lines = h.runStore.read(id, 0, 1000).joinToString("\n") { it.line }
    assertTrue(lines.contains("lemon") && lines.contains("RATE_LIMITED"), lines)
    assertTrue(
        logs.lines.any {
          it.contains("lemon") && it.contains("RATE_LIMITED") && !it.contains(promptMarker)
        },
        logs.lines.toString(),
    )
  }

  // ---- streams (WI-47) ----

  /** A pipeline that streams a chat completion, with usage, to its end, and says what it pulled. */
  private fun streamBody() =
      """
      OpenAiAccessor lemon = context.getAccessors().openAiCompatible("lemon");
      try (OpenAiStream s = lemon.stream(new OpenAiRequest("chat.completions", "{\"model\":\"$modelMarker\",\"stream_options\":{\"include_usage\":true},\"messages\":[{\"role\":\"user\",\"content\":\"$promptMarker\"}]}"))) {
        int n = 0;
        while (s.next() != null) n++;
        context.getFiles().writeText(FileScope.PIPELINE_SHARED, "events", Integer.toString(n));
      } catch (ResourceAccessException e) {
        context.getFiles().writeText(FileScope.PIPELINE_SHARED, "failure", e.getFailure().name());
      }
      """
          .trimIndent()

  @Test
  fun `a stream is timed by its first event, its longest wait and its generation, and its tokens are counted`() {
    define()
    server.chunkDelayMillis = 120

    ran("streamer", streamBody())

    val base = arrayOf("resource" to "lemon", "type" to "openai-compatible")
    assertEquals(
        1,
        histogramCount(
            "runline.resources.openai.stream.first_chunk.duration",
            *base,
            "endpoint" to "chat.completions",
        ),
    )
    assertEquals(
        1,
        histogramCount(
            "runline.resources.openai.stream.max_gap.duration",
            *base,
            "endpoint" to "chat.completions",
        ),
    )
    val gap =
        metric("runline.resources.openai.stream.max_gap.duration")!!
            .histogramData
            .points
            .single()
            .sum
    assertTrue(gap in 0.1..1.5, "the longest wait was $gap s")
    assertEquals(
        1,
        histogramCount(
            "runline.resources.openai.generation.duration",
            *base,
            "endpoint" to "chat.completions",
        ),
    )
    assertEquals(
        1,
        counter(
            "runline.resources.openai.requests",
            *base,
            "endpoint" to "chat.completions",
            "outcome" to "ok",
        ),
    )
    assertEquals(3, counter("runline.resources.openai.tokens", *base, "kind" to "prompt"))
    assertEquals(4, counter("runline.resources.openai.tokens", *base, "kind" to "completion"))
    assertEquals(
        0,
        metric("runline.resources.openai.requests.in_flight")!!.longSumData.points.single().value,
    )
  }
}
