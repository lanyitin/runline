package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.accessors.fake.FakeOpenAiServer
import dev.lawlan.runline.engine.run.RunState
import dev.lawlan.runline.engine.secret.KeystoreSecretStore
import dev.lawlan.runline.engine.secret.SecretValue
import dev.lawlan.runline.engine.support.CapturedLogs
import dev.lawlan.runline.engine.support.Keystores
import dev.lawlan.runline.engine.support.RunHarness
import dev.lawlan.runline.engine.support.RunHarness.Companion.usingTyped
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import java.nio.file.Files
import java.time.Duration
import java.util.UUID
import kotlin.test.*

/**
 * The end-to-end case of the secret non-leak rules (WI-46, WI-41): a service that echoes the API
 * key back, in an error, in headers, in a body. The key is a real entry of a real keystore, the
 * request is a real request. Whatever the Engine says is searched for the key: the run's failure,
 * the run's log, the Engine's log, the trace, the metrics, and what a pipeline is given back.
 */
class OpenAiKeyLeakTest {
  private val key = "sk-leak-marker-5D1E77A0B9"
  private val keystores = Keystores()
  private val passwordFile = keystores.passwordFile()
  private val keystore = keystores.pkcs12("leak.p12", mapOf("lemon-key" to key))
  private val secrets =
      KeystoreSecretStore.open(keystore, SecretValue(Files.readString(passwordFile).trim()))
  private val server = FakeOpenAiServer()
  private val logs = CapturedLogs()
  private val spans = InMemorySpanExporter.create()
  private val metrics = InMemoryMetricReader.create()
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
          secrets = secrets,
          openAiObserver = OpenAiTelemetry(otel, usage),
      )
  private val runs = mutableListOf<UUID>()

  init {
    h.defineOpenAi(
        "lemon",
        """{"baseUrl":"${server.baseUrl}","endpoints":["chat.completions","files.create","audio.speech","files.content"]}""",
        alias = "lemon-key",
    )
  }

  @AfterTest
  fun close() {
    h.close()
    server.close()
    logs.close()
    secrets.close()
  }

  private fun run(name: String, body: String): dev.lawlan.runline.engine.run.RunRecord {
    val hash = h.upload(name, body, declaration = usingTyped("lemon" to "openai-compatible"))
    val id = h.start(hash, name)
    runs += id
    return h.awaitEnd(id)
  }

  /** Everything the Engine says about the runs so far, as text. */
  private fun everything(): String = buildString {
    runs.forEach { id ->
      val record = h.record(id)
      append(record.failure.toString()).append('\n')
      append(h.runStore.read(id, 0, 10_000).joinToString("\n") { it.line }).append('\n')
    }
    append(logs.lines.joinToString("\n")).append('\n')
    spans.finishedSpanItems.forEach { append(it.toString()).append(it.events).append('\n') }
    metrics.collectAllMetrics().forEach { append(it.toString()).append('\n') }
  }

  private val call =
      """
      OpenAiAccessor lemon = context.getAccessors().openAiCompatible("lemon");
      OpenAiResponse r = lemon.call(new OpenAiRequest("chat.completions", "{}"));
      """
          .trimIndent()

  private fun echoingError() {
    server.script = { request, response ->
      val sent = request.header("authorization").orEmpty()
      response.json(
          401,
          """{"error":{"message":"Incorrect API key provided: $key (you sent '$sent')","code":"invalid_api_key"}}""",
          mapOf(
              "X-Echo-Authorization" to sent,
              "X-Debug" to "key=$key",
              "WWW-Authenticate" to "Bearer realm=\"fake\", error=\"$key\"",
              "Set-Cookie" to "session=$key",
          ),
      )
      true
    }
  }

  @Test
  fun `a service that echoes the key in an error, so the pipeline is told the category and the status, and no surface has the key`() {
    echoingError()
    val caught =
        run(
            "catches",
            """
            OpenAiAccessor lemon = context.getAccessors().openAiCompatible("lemon");
            String seen;
            try { lemon.call(new OpenAiRequest("chat.completions", "{}")); seen = "no error"; }
            catch (ResourceAccessException e) {
              seen = e.getFailure().name() + "|" + e.getStatus() + "|" + e.getMessage() + "|" + e.getErrorId() + "|" + e.getCause();
            }
            context.getFiles().writeText(FileScope.PIPELINE_SHARED, "seen", seen);
            """
                .trimIndent(),
        )
    val escaped = run("escapes", call)

    assertEquals(RunState.SUCCEEDED, caught.state, caught.failure?.message)
    val seen = Files.readString(h.shared("catches", "seen"))
    assertTrue(seen.startsWith("DENIED|401|"), seen)
    assertFalse(seen.contains(key), seen)
    assertEquals(RunState.FAILED, escaped.state)
    assertTrue(escaped.failure!!.message!!.contains("DENIED"), escaped.failure!!.message)
    assertFalse(everything().contains(key), "the key is in something the Engine said")
    assertEquals(2, server.requests.size, "the key did go out, to the service, with each request")
  }

  @Test
  fun `a service that echoes the key in the headers of an answer, so the headers the pipeline gets have no credential and no key`() {
    server.script = { request, response ->
      response.json(
          200,
          "{}",
          mapOf(
              "X-Debug" to "you sent ${request.header("authorization")}",
              "X-Api-Key" to key,
              "Set-Cookie" to "session=$key",
              "X-Request-Id" to "req-9",
          ),
      )
      true
    }

    val ended =
        run(
            "headers",
            """
            OpenAiAccessor lemon = context.getAccessors().openAiCompatible("lemon");
            OpenAiResponse r = lemon.call(new OpenAiRequest("chat.completions", "{}"));
            context.getFiles().writeText(FileScope.PIPELINE_SHARED, "headers", r.getHeaders().toString());
            """
                .trimIndent(),
        )

    assertEquals(RunState.SUCCEEDED, ended.state, ended.failure?.message)
    val headers = Files.readString(h.shared("headers", "headers"))
    assertFalse(headers.contains(key), headers)
    assertTrue(headers.contains("x-debug=[you sent Bearer ***]"), headers)
    assertTrue(headers.contains("x-request-id=[req-9]"), headers)
    assertFalse(headers.contains("x-api-key") || headers.contains("set-cookie"), headers)
    assertFalse(everything().contains(key))
  }

  @Test
  fun `a service that echoes the key in the body of an answer, the pipeline gets the body, and the key is masked wherever the Engine says it`() {
    server.script = { request, response ->
      response.json(200, """{"echo":"${request.header("authorization")}"}""")
      true
    }

    // The body is the pipeline's to use (ADR-019: the Engine does not touch it); what the
    // pipeline does with it, here failing with it, is what the masking is for.
    val ended =
        run(
            "echoes",
            """
            OpenAiAccessor lemon = context.getAccessors().openAiCompatible("lemon");
            OpenAiResponse r = lemon.call(new OpenAiRequest("chat.completions", "{}"));
            throw new RuntimeException("the service said " + r.getBody());
            """
                .trimIndent(),
        )

    assertEquals(RunState.FAILED, ended.state)
    assertTrue(ended.failure!!.message!!.contains("the service said"), ended.failure!!.message)
    assertFalse(everything().contains(key), "the key is in something the Engine said")
  }

  @Test
  fun `a service that echoes the key in a stream, in its events, its headers and an error in its middle, gives the pipeline no key and no surface has it`() {
    server.script = { request, response ->
      response.beginChunked(
          200,
          mapOf(
              "Content-Type" to "text/event-stream",
              "X-Debug" to "you sent ${request.header("authorization")}",
              "X-Api-Key" to key,
          ),
      )
      response.event("""{"echo":"${request.header("authorization")}"}""")
      response.event("""{"error":"Incorrect API key provided: $key"}""")
      response.abort()
      throw dev.lawlan.runline.accessors.fake.ClientGone()
    }

    val ended =
        run(
            "streams",
            """
            OpenAiAccessor lemon = context.getAccessors().openAiCompatible("lemon");
            StringBuilder seen = new StringBuilder();
            try (OpenAiStream s = lemon.stream(new OpenAiRequest("chat.completions", "{}"))) {
              seen.append(s.getHeaders()).append("|");
              String data;
              while ((data = s.next()) != null) seen.append(data).append("|");
            } catch (ResourceAccessException e) {
              seen.append(e.getFailure().name()).append("|").append(e.getStatus()).append("|").append(e.getMessage()).append("|").append(e.getCause());
            }
            context.getFiles().writeText(FileScope.PIPELINE_SHARED, "seen", seen.toString());
            throw new RuntimeException("the stream said " + seen);
            """
                .trimIndent(),
        )

    assertEquals(RunState.FAILED, ended.state)
    val seen = Files.readString(h.shared("streams", "seen"))
    assertFalse(seen.contains(key), seen)
    assertTrue(seen.contains("Bearer ***"), seen)
    assertTrue(seen.contains("CONNECTION_FAILED"), seen)
    assertFalse(seen.contains("x-api-key"), seen)
    assertFalse(everything().contains(key), "the key is in something the Engine said")
  }

  @Test
  fun `a service that echoes the key to an upload or a download, in its errors and its headers, gives the pipeline no key and no surface has it`() {
    server.script = { request, response ->
      val sent = request.header("authorization").orEmpty()
      val echo =
          mapOf(
              "X-Debug" to "you sent $sent",
              "X-Api-Key" to key,
              "Content-Disposition" to "attachment; filename=\"$key.bin\"",
              "Set-Cookie" to "session=$key",
          )
      if (request.path.endsWith("/files")) {
        response.json(400, """{"error":{"message":"bad file, key $key"}}""", echo)
      } else {
        response.complete(200, "audio".toByteArray(), echo + ("Content-Type" to "audio/mpeg"))
      }
      true
    }

    val ended =
        run(
            "echoed",
            """
            OpenAiAccessor lemon = context.getAccessors().openAiCompatible("lemon");
            StringBuilder seen = new StringBuilder();
            try {
              lemon.call(new OpenAiRequest("files.create", null, java.util.Collections.<String,String>emptyMap(), java.util.Collections.<String,String>emptyMap(), new OpenAiTimeouts(),
                  java.util.Collections.singletonMap("purpose", "batch"),
                  java.util.Collections.singletonList(OpenAiUpload.bytes("file", "a.txt", new byte[] {1})),
                  new OpenAiSizes()));
            } catch (ResourceAccessException e) {
              seen.append(e.getFailure().name()).append("|").append(e.getStatus()).append("|").append(e.getMessage()).append("|").append(e.getCause()).append("#");
            }
            OpenAiBinaryResponse mem = lemon.download(new OpenAiRequest("audio.speech", "{}"));
            seen.append(mem.getHeaders()).append("#");
            OpenAiStoredResponse file = lemon.downloadTo(new OpenAiRequest("audio.speech", "{}"), new OpenAiFile(FileScope.PIPELINE_SHARED, "a.mp3"));
            seen.append(file.getHeaders()).append("#");
            try (OpenAiByteStream s = lemon.streamBytes(new OpenAiRequest("audio.speech", "{}"))) {
              seen.append(s.getHeaders());
            }
            context.getFiles().writeText(FileScope.PIPELINE_SHARED, "seen", seen.toString());
            throw new RuntimeException("it said " + seen);
            """
                .trimIndent(),
        )

    assertEquals(RunState.FAILED, ended.state)
    val seen = Files.readString(h.shared("echoed", "seen"))
    assertTrue(seen.startsWith("REQUEST_REJECTED|400|"), seen)
    assertFalse(seen.contains(key), seen)
    assertTrue(seen.contains("Bearer ***"), seen)
    assertFalse(seen.contains("x-api-key") || seen.contains("set-cookie"), seen)
    assertFalse(everything().contains(key), "the key is in something the Engine said")
    assertEquals(4, server.requests.size)
  }

  @Test
  fun `a service that echoes the key into the bytes of an answer, so the pipeline gets nothing of it and no file keeps it`() {
    server.script = { request, response ->
      response.beginChunked(200, mapOf("Content-Type" to "audio/mpeg"))
      response.chunk(
          ByteArray(100) { 1 } +
              request.header("authorization")!!.removePrefix("Bearer ").toByteArray()
      )
      response.endChunked()
      true
    }

    val ended =
        run(
            "bytes",
            """
            OpenAiAccessor lemon = context.getAccessors().openAiCompatible("lemon");
            StringBuilder seen = new StringBuilder();
            try { lemon.download(new OpenAiRequest("audio.speech", "{}")); seen.append("no error;"); }
            catch (ResourceAccessException e) { seen.append(e.getFailure().name()).append(";"); }
            try { lemon.downloadTo(new OpenAiRequest("audio.speech", "{}"), new OpenAiFile(FileScope.PIPELINE_SHARED, "a.mp3")); seen.append("no error;"); }
            catch (ResourceAccessException e) { seen.append(e.getFailure().name()).append(";"); }
            context.getFiles().writeText(FileScope.PIPELINE_SHARED, "seen", seen.toString());
            """
                .trimIndent(),
        )

    assertEquals(RunState.SUCCEEDED, ended.state, ended.failure?.message)
    assertEquals(
        "SECRET_IN_RESPONSE;SECRET_IN_RESPONSE;",
        Files.readString(h.shared("bytes", "seen")),
    )
    assertFalse(Files.exists(h.shared("bytes", "a.mp3")))
    Files.list(h.shared("bytes", "seen").parent).use { files ->
      files.forEach { assertFalse(Files.readString(it).contains(key), "$it holds the key") }
    }
    assertFalse(everything().contains(key), "the key is in something the Engine said")
  }
}
