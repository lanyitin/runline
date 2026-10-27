package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.analyzer.AllowListEntry
import dev.lawlan.runline.analyzer.Verdict
import dev.lawlan.runline.engine.config.TraceExportTuning
import dev.lawlan.runline.engine.run.RunState
import dev.lawlan.runline.engine.secret.KeystoreSecretStore
import dev.lawlan.runline.engine.secret.SecretValue
import dev.lawlan.runline.engine.support.CapturedLogs
import dev.lawlan.runline.engine.support.Keystores
import dev.lawlan.runline.engine.support.OtlpReceiver
import dev.lawlan.runline.engine.support.QUICK_TRACE_EXPORT
import dev.lawlan.runline.engine.support.RunHarness
import dev.lawlan.runline.engine.support.RunHarness.Companion.usingTyped
import dev.lawlan.runline.engine.support.TestRuntime
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import java.nio.file.Files
import java.time.Duration
import java.util.zip.ZipFile
import kotlin.io.path.isDirectory
import kotlin.io.path.relativeTo
import kotlin.test.*

/**
 * `trace-export` resources through the whole run machinery with everything real (ADR-46, #51):
 * PostgreSQL, the coordinator, scheduler and Runner (a class loader per run), pipelines compiled
 * from source against core's contract, a real PKCS12 keystore, and real OTLP receivers on sockets.
 * What reaches a receiver, with which key, and that a run does not feel the receiver at all.
 */
class TraceExportRunTest {
  private val keystores = Keystores()
  private val passwordFile = keystores.passwordFile()
  private val keystore =
      keystores.pkcs12(
          "run.p12",
          mapOf("langfuse-auth" to "Basic cnVuLW9uZQ==", "other-auth" to "Basic cnVuLXR3bw=="),
      )
  private val secrets =
      KeystoreSecretStore.open(keystore, SecretValue(Files.readString(passwordFile).trim()))
  private val receiver = OtlpReceiver()
  private val other = OtlpReceiver()
  private val logs = CapturedLogs()
  private val engineSpans = InMemorySpanExporter.create()
  private val engineMetrics = InMemoryMetricReader.create()
  private val otel =
      OpenTelemetrySdk.builder()
          .setTracerProvider(
              SdkTracerProvider.builder()
                  .addSpanProcessor(SimpleSpanProcessor.create(engineSpans))
                  .build()
          )
          .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(engineMetrics).build())
          .build()
  private val harnesses = mutableListOf<RunHarness>()

  private fun harness(tuning: TraceExportTuning = QUICK_TRACE_EXPORT) =
      RunHarness(
              maxConcurrent = 4,
              resourceWaitTimeout = Duration.ofHours(1),
              secrets = secrets,
              openTelemetry = otel,
              traceExportTuning = tuning,
              allowList =
                  listOf(
                          "java.lang",
                          "java.util",
                          "java.io",
                          "java.time",
                          "kotlin",
                          "org.jetbrains.annotations",
                      )
                      .map { AllowListEntry(it) },
          )
          .also { harnesses += it }

  @AfterTest
  fun close() {
    harnesses.forEach { it.close() }
    receiver.close()
    other.close()
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

  private val declaration = usingTyped("tracing" to "trace-export")

  /** Statements that submit a span of [role], named [name], with local identifier [id]. */
  private fun span(name: String, id: String, parent: String? = null, role: String = "tracing") =
      """
      context.getAccessors().traceExport("$role").submit(new SpanRecord("$name", java.time.Instant.now(), java.time.Instant.now(), SpanStatus.OK, null, new java.util.HashMap<String, Object>(), "$id", ${parent?.let { "\"$it\"" } ?: "null"}));
      """
          .trimIndent()

  private val traced =
      span("child", "c", "p") +
          "\n" +
          span("parent", "p") +
          "\n" +
          """context.getFiles().writeText(FileScope.PIPELINE_SHARED, "done", "x");"""

  // ---- what reaches the receiver ----

  @Test
  fun `a run's spans reach the receiver with the service name, the hierarchy and one trace`() {
    val h = harness()
    h.defineTraceExport("tracing", receiver.endpoint, serviceName = "transcribe")
    val hash = h.upload("traced", traced, declaration = declaration)

    val first = h.awaitEnd(h.start(hash, "traced"))
    val second = h.awaitEnd(h.start(hash, "traced"))

    assertEquals(RunState.SUCCEEDED, first.state, first.failure?.message)
    assertEquals(RunState.SUCCEEDED, second.state, second.failure?.message)
    await("four spans") { receiver.spans.size == 4 }
    assertTrue(receiver.spans.all { it.serviceName == "transcribe" })
    val byTrace = receiver.spans.groupBy { it.traceId }
    assertEquals(2, byTrace.size)
    for (spans in byTrace.values) {
      val named = spans.associateBy { it.name }
      assertEquals(setOf("child", "parent"), named.keys)
      assertEquals(named.getValue("parent").spanId, named.getValue("child").parentSpanId)
      assertEquals("", named.getValue("parent").parentSpanId)
    }
    assertEquals("Basic cnVuLW9uZQ==", receiver.requests.first().authorization)
  }

  @Test
  fun `a pipeline that traces is still safe, and no OpenTelemetry class is on the run's class path`() {
    val h = harness()
    val hash = h.upload("traced", traced, declaration = declaration)

    assertEquals(Verdict.SAFE, h.definitions.find(hash, "alice", "traced")!!.verdict)
    for (location in TestRuntime.jars) {
      val names =
          if (location.isDirectory())
              Files.walk(location).use { stream ->
                stream
                    .filter { !it.isDirectory() }
                    .map { it.relativeTo(location).toString() }
                    .toList()
              }
          else
              ZipFile(location.toFile()).use { zip ->
                zip.entries().asSequence().map { it.name }.toList()
              }
      assertTrue(names.none { it.startsWith("io/opentelemetry") }, "$location")
    }
  }

  // ---- without tracing ----

  @Test
  fun `a pipeline whose role is not bound runs as it does without tracing, and nothing is sent`() {
    val h = harness()
    val hash = h.upload("traced", traced, declaration = declaration)

    val run = h.awaitEnd(h.start(hash, "traced"))

    assertEquals(RunState.SUCCEEDED, run.state, run.failure?.message)
    assertTrue(Files.exists(h.shared("traced", "done")))
    assertTrue(receiver.requests.isEmpty())
  }

  @Test
  fun `a pipeline whose resource is disabled runs as it does without tracing, and nothing is sent`() {
    val h = harness()
    h.defineTraceExport("tracing", receiver.endpoint, enabled = false)
    val hash = h.upload("traced", traced, declaration = declaration)

    val run = h.awaitEnd(h.start(hash, "traced"))

    assertEquals(RunState.SUCCEEDED, run.state, run.failure?.message)
    Thread.sleep(300)
    assertTrue(receiver.requests.isEmpty())
  }

  // ---- a receiver that is not well ----

  @Test
  fun `a receiver that never answers costs a run nothing, and its end does not wait for it`() {
    receiver.hang = true
    val tuning = QUICK_TRACE_EXPORT.copy(flushWait = Duration.ofMillis(300))
    val h = harness(tuning)
    h.defineTraceExport("tracing", receiver.endpoint)
    val hash = h.upload("traced", traced, declaration = declaration)

    val started = System.nanoTime()
    val run = h.awaitEnd(h.start(hash, "traced"))
    val tookMillis = (System.nanoTime() - started) / 1_000_000

    assertEquals(RunState.SUCCEEDED, run.state, run.failure?.message)
    assertTrue(tookMillis < 5000, "the run took $tookMillis ms")
  }

  @Test
  fun `the end of a run that traced to a receiver that never answers does not hold up the next run`() {
    receiver.hang = true
    val tuning =
        QUICK_TRACE_EXPORT.copy(
            flushWait = Duration.ofSeconds(4),
            exportTimeout = Duration.ofSeconds(30),
        )
    val h = harness(tuning)
    h.defineTraceExport("tracing", receiver.endpoint)
    val traced = h.upload("traced", traced, declaration = declaration)
    val plain =
        h.upload(
            "plain",
            """context.getFiles().writeText(FileScope.PIPELINE_SHARED, "done", "x");""",
        )

    h.awaitEnd(h.start(traced, "traced"))
    await("the span at the receiver") { receiver.requests.isNotEmpty() }
    val started = System.nanoTime()
    val next = h.awaitEnd(h.start(plain, "plain"))
    val tookMillis = (System.nanoTime() - started) / 1_000_000

    assertEquals(RunState.SUCCEEDED, next.state, next.failure?.message)
    assertTrue(tookMillis < 2500, "the next run took $tookMillis ms")
  }

  @Test
  fun `a receiver that answers with an error costs the batch, not the run`() {
    receiver.answer = { 500 }
    val h = harness()
    h.defineTraceExport("tracing", receiver.endpoint)
    val hash = h.upload("traced", traced, declaration = declaration)

    val run = h.awaitEnd(h.start(hash, "traced"))

    assertEquals(RunState.SUCCEEDED, run.state, run.failure?.message)
    await("the drop") {
      engineMetrics
          .collectAllMetrics()
          .filter { it.name == "runline.trace_export.spans.dropped" }
          .flatMap { it.longSumData.points }
          .sumOf { it.value } == 2L
    }
    assertTrue(logs.lines.any { it.contains("trace-export resource tracing dropped") })
  }

  // ---- keys and endpoints ----

  @Test
  fun `after the keystore is reloaded the next run exports with the new key, without a deployment`() {
    val h = harness()
    h.defineTraceExport("tracing", receiver.endpoint)
    val hash = h.upload("traced", traced, declaration = declaration)
    h.awaitEnd(h.start(hash, "traced"))
    await("the first export") { receiver.spans.size == 2 }

    keystores.deleteEntry(keystore, "langfuse-auth", passwordFile)
    keystores.importSecret(keystore, "langfuse-auth", "Basic cm90YXRlZA==", passwordFile)
    secrets.reload()
    h.awaitEnd(h.start(hash, "traced"))
    await("the second export") { receiver.spans.size == 4 }

    assertEquals(
        listOf("Basic cnVuLW9uZQ==", "Basic cm90YXRlZA=="),
        receiver.requests.map { it.authorization }.distinct(),
    )
  }

  @Test
  fun `two resources with their own endpoints and keys do not meet, nor does the Engine's own telemetry`() {
    val h = harness()
    h.defineTraceExport("tracing", receiver.endpoint, serviceName = "service-one")
    h.defineTraceExport(
        "audit",
        other.endpoint,
        serviceName = "service-two",
        alias = "other-auth",
    )
    val both = span("on-one", "a") + "\n" + span("on-two", "b", role = "audit")
    val hash =
        h.upload(
            "both",
            both,
            declaration = usingTyped("tracing" to "trace-export", "audit" to "trace-export"),
        )

    val run = h.awaitEnd(h.start(hash, "both"))

    assertEquals(RunState.SUCCEEDED, run.state, run.failure?.message)
    await("a span at each") { receiver.spans.isNotEmpty() && other.spans.isNotEmpty() }
    assertEquals(listOf("on-one"), receiver.spans.map { it.name })
    assertEquals(listOf("service-one"), receiver.spans.map { it.serviceName })
    assertEquals("Basic cnVuLW9uZQ==", receiver.requests.single().authorization)
    assertEquals(listOf("on-two"), other.spans.map { it.name })
    assertEquals(listOf("service-two"), other.spans.map { it.serviceName })
    assertEquals("Basic cnVuLXR3bw==", other.requests.single().authorization)
    // The Engine's own telemetry has none of the pipeline's spans, nor a span per submission.
    val engineNames = engineSpans.finishedSpanItems.map { it.name }
    assertFalse(engineNames.any { it == "on-one" || it == "on-two" }, "$engineNames")
    assertFalse(engineNames.any { it.contains("trace.submit") }, "$engineNames")
  }
}
