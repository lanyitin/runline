package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.accessors.fake.FakeOpenAiServer
import dev.lawlan.runline.analyzer.AllowListEntry
import dev.lawlan.runline.analyzer.Verdict
import dev.lawlan.runline.engine.artifact.Visibility
import dev.lawlan.runline.engine.auth.ApiIdentity
import dev.lawlan.runline.engine.auth.Role
import dev.lawlan.runline.engine.run.CancelResult
import dev.lawlan.runline.engine.run.RunState
import dev.lawlan.runline.engine.secret.KeystoreSecretStore
import dev.lawlan.runline.engine.secret.SecretValue
import dev.lawlan.runline.engine.support.Keystores
import dev.lawlan.runline.engine.support.RunHarness
import dev.lawlan.runline.engine.support.RunHarness.Companion.holdUntilReleased
import dev.lawlan.runline.engine.support.RunHarness.Companion.usingTyped
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import kotlin.test.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `openai-compatible` resources through the whole run machinery with everything real: PostgreSQL,
 * the real coordinator, scheduler and Runner (a class loader per run), pipelines compiled from
 * source, a real PKCS12 keystore, and a service that is the Fake on a real socket (WI-46, ADR-019).
 * The requests, the keys and how many requests the service has at once are what the service saw.
 */
class OpenAiResourceRunTest {
  private val key = "sk-run-0123456789abcdef"
  private val keystores = Keystores()
  private val passwordFile = keystores.passwordFile()
  private val server = FakeOpenAiServer()
  private val extras = mutableListOf<AutoCloseable>(server)
  private val admin = ApiIdentity("root", Role.ADMIN)

  private val keystore: Path = keystores.pkcs12("run.p12", mapOf("lemon-key" to key))
  private val secrets: KeystoreSecretStore =
      KeystoreSecretStore.open(
          keystore,
          SecretValue(Files.readString(passwordFile).trim()),
      )
  private val harnesses = mutableListOf<RunHarness>()

  init {
    extras += secrets
  }

  private fun harness(maxConcurrent: Int = 4, maxBytesPerScope: Long = 1_000_000) =
      RunHarness(
              maxBytesPerScope = maxBytesPerScope,
              maxConcurrent = maxConcurrent,
              resourceWaitTimeout = Duration.ofHours(1),
              secrets = secrets,
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
  fun closeAll() {
    harnesses.forEach { it.close() }
    extras.forEach { it.close() }
  }

  private fun settings(base: String = server.baseUrl, extra: String = "") =
      """{"baseUrl":"$base"${if (extra.isEmpty()) "" else ",$extra"}}"""

  private fun java(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  /** A pipeline body: one call of [endpoint], its outcome written to the shared file [file]. */
  private fun call(
      file: String,
      endpoint: String = "chat.completions",
      body: String? = """{"messages":[{"role":"user","content":"hi"}]}""",
      timeouts: String = "new OpenAiTimeouts()",
  ) =
      """
      OpenAiAccessor lemon = context.getAccessors().openAiCompatible("lemon");
      String result;
      try {
        OpenAiResponse r = lemon.call(new OpenAiRequest(${java(endpoint)}, ${body?.let(::java) ?: "null"}, java.util.Collections.<String,String>emptyMap(), java.util.Collections.<String,String>emptyMap(), $timeouts));
        result = "ok|" + r.getStatus() + "|" + r.getBody();
      } catch (ResourceAccessException e) {
        result = e.getFailure().name() + "|" + e.getStatus();
      }
      context.getFiles().writeText(FileScope.PIPELINE_SHARED, "$file", result);
      """
          .trimIndent()

  private val declaration = usingTyped("lemon" to "openai-compatible")

  private fun RunHarness.result(pipeline: String, file: String) =
      Files.readString(shared(pipeline, file))

  private fun await(what: String, seconds: Long = 15, condition: () -> Boolean) {
    val deadline = System.nanoTime() + seconds * 1_000_000_000
    while (!condition()) {
      check(System.nanoTime() < deadline) { "gave up waiting for $what" }
      Thread.sleep(10)
    }
  }

  // ---- calling ----

  @Test
  fun `a run calls the service through its accessor, with the key and the defaults, and never sees the key`() {
    val h = harness()
    h.defineOpenAi(
        "lemon",
        settings(extra = """"defaults":{"model":"m1","max_tokens":50},"organization":"org-7""""),
        alias = "lemon-key",
    )
    val hash = h.upload("caller", call("answer"), declaration = declaration)

    val run = h.awaitEnd(h.start(hash, "caller"))

    assertEquals(RunState.SUCCEEDED, run.state, run.failure?.message)
    val answer = h.result("caller", "answer")
    assertTrue(answer.startsWith("ok|200|"), answer)
    assertTrue(answer.contains("chat.completion"))
    assertFalse(answer.contains(key))
    val seen = server.requests.single()
    assertEquals("Bearer $key", seen.header("authorization"))
    assertEquals("org-7", seen.header("openai-organization"))
    val sent = Json.parseToJsonElement(seen.body).jsonObject
    assertEquals("m1", sent["model"]!!.jsonPrimitive.content)
    assertEquals("50", sent["max_tokens"]!!.jsonPrimitive.content)
    assertTrue("messages" in sent)
  }

  @Test
  fun `a pipeline that uses the accessor of an openai-compatible resource is still safe`() {
    val h = harness()
    val hash = h.upload("caller", call("answer"), declaration = declaration)

    assertEquals(Verdict.SAFE, h.definitions.find(hash, "caller")!!.verdict)
  }

  @Test
  fun `what the service answers and what the rules refuse come back as categories with the status`() {
    val h = harness()
    h.defineOpenAi(
        "lemon",
        settings(extra = """"lockedParameters":["temperature"],"defaults":{"temperature":0.5}"""),
    )
    val locked =
        h.upload(
            "locked-p",
            call("locked", body = """{"temperature":0.9}"""),
            declaration = declaration,
        )
    val unknown =
        h.upload(
            "unknown-p",
            call("unknown", endpoint = "files.delete", body = null),
            declaration = declaration,
        )
    val limited = h.upload("limited-p", call("limited"), declaration = declaration)
    server.script = { _, response ->
      response.json(429, "{}")
      true
    }

    for (name in listOf("locked-p", "unknown-p", "limited-p")) {
      val hashOf = mapOf("locked-p" to locked, "unknown-p" to unknown, "limited-p" to limited)
      assertEquals(RunState.SUCCEEDED, h.awaitEnd(h.start(hashOf.getValue(name), name)).state)
    }

    assertEquals("PARAMETER_LOCKED|null", h.result("locked-p", "locked"))
    assertEquals("ENDPOINT_NOT_ENABLED|null", h.result("unknown-p", "unknown"))
    assertEquals("RATE_LIMITED|429", h.result("limited-p", "limited"))
  }

  @Test
  fun `a pipeline can shorten the limits on time of a call`() {
    val h = harness()
    h.defineOpenAi("lemon", settings())
    server.responseDelayMillis = 3000
    val hash =
        h.upload(
            "impatient",
            call(
                "outcome",
                timeouts =
                    "new OpenAiTimeouts(null, java.time.Duration.ofMillis(300), null, null, null)",
            ),
            declaration = declaration,
        )

    val run = h.awaitEnd(h.start(hash, "impatient"))

    assertEquals(RunState.SUCCEEDED, run.state, run.failure?.message)
    assertEquals("FIRST_BYTE_TIMEOUT|null", h.result("impatient", "outcome"))
  }

  @Test
  fun `a key that the keystore cannot give fails the call, not the run, and nothing is sent`() {
    val h = harness()
    h.defineOpenAi("lemon", settings(), alias = "not-in-the-keystore")
    val hash = h.upload("caller", call("outcome"), declaration = declaration)

    val run = h.awaitEnd(h.start(hash, "caller"))

    assertEquals(RunState.SUCCEEDED, run.state, run.failure?.message)
    assertEquals("SECRET_UNAVAILABLE|null", h.result("caller", "outcome"))
    assertEquals(0, server.requests.size)
  }

  // ---- how many requests the service has at once ----

  /** Calls the service from [threads] threads of the run at once. */
  private fun burst(threads: Int) =
      """
      final OpenAiAccessor lemon = context.getAccessors().openAiCompatible("lemon");
      Thread[] ts = new Thread[$threads];
      final String[] out = new String[$threads];
      for (int i = 0; i < $threads; i++) {
        final int n = i;
        ts[i] = new Thread(() -> {
          try { lemon.call(new OpenAiRequest("chat.completions", "{}")); out[n] = "ok"; }
          catch (ResourceAccessException e) { out[n] = e.getFailure().name(); }
        });
        ts[i].start();
      }
      try { for (Thread t : ts) t.join(); } catch (InterruptedException e) { throw new RuntimeException(e); }
      context.getFiles().writeText(FileScope.PIPELINE_SHARED, "out", String.join(",", out));
      """
          .trimIndent()

  @Test
  fun `with capacity one and one request per run the service never has two requests, however many runs and threads`() {
    val h = harness(maxConcurrent = 4)
    h.defineOpenAi("lemon", settings(), capacity = 1)
    server.responseDelayMillis = 120
    val hash = h.upload("burst", burst(3), declaration = declaration)

    val runs = List(3) { h.start(hash, "burst") }
    runs.forEach { assertEquals(RunState.SUCCEEDED, h.awaitEnd(it, seconds = 60).state) }

    assertEquals(9, server.requests.size)
    assertEquals(1, server.peakInFlight)
    assertEquals("ok,ok,ok", h.result("burst", "out"))
  }

  @Test
  fun `with capacity two the service has two requests at the most, and does have two`() {
    val h = harness(maxConcurrent = 4)
    h.defineOpenAi("lemon", settings(), capacity = 2)
    server.responseDelayMillis = 400
    val hash = h.upload("burst", burst(3), declaration = declaration)

    val runs = List(4) { h.start(hash, "burst") }
    runs.forEach { assertEquals(RunState.SUCCEEDED, h.awaitEnd(it, seconds = 60).state) }

    assertEquals(12, server.requests.size)
    assertEquals(2, server.peakInFlight)
  }

  @Test
  fun `with more requests per run the limit is capacity times that, and no more`() {
    val h = harness(maxConcurrent = 4)
    h.defineOpenAi("lemon", settings(extra = """"requestsPerRun":2"""), capacity = 2)
    server.responseDelayMillis = 400
    val hash = h.upload("burst", burst(5), declaration = declaration)

    val runs = List(3) { h.start(hash, "burst") }
    runs.forEach { assertEquals(RunState.SUCCEEDED, h.awaitEnd(it, seconds = 60).state) }

    assertEquals(15, server.requests.size)
    assertEquals(4, server.peakInFlight)
  }

  @Test
  fun `lowering the capacity does not take the resource from those who hold it, so the service can have more for a while`() {
    val h = harness(maxConcurrent = 4)
    h.defineOpenAi("lemon", settings(), capacity = 2)
    server.responseDelayMillis = 1500
    val hash = h.upload("burst", burst(1), declaration = declaration)
    val first = h.start(hash, "burst")
    val second = h.start(hash, "burst")
    await("two requests at the service") { server.inFlight == 2 }

    h.resourceAdmin.update("lemon", 1, null, admin)
    val third = h.start(hash, "burst")
    h.await(third, RunState.WAITING_FOR_RESOURCES)

    assertEquals(2, server.inFlight, "the holders keep their requests")
    listOf(first, second, third).forEach {
      assertEquals(RunState.SUCCEEDED, h.awaitEnd(it, seconds = 60).state)
    }
    assertEquals(2, server.peakInFlight, "the third waited for the holders to end")
  }

  // ---- letting go ----

  /** A call that the service never answers, so that it is in flight until something cuts it. */
  private val stuck:
      (
          dev.lawlan.runline.accessors.fake.FakeRequest,
          dev.lawlan.runline.accessors.fake.FakeResponse,
      ) -> Boolean =
      { _, response ->
        response.hang()
        true
      }

  @Test
  fun `a forced release cuts the request in flight, the service sees the connection go, and the next waiter gets the service`() {
    val h = harness(maxConcurrent = 2)
    h.defineOpenAi("lemon", settings(), capacity = 1)
    server.script = stuck
    val holder =
        h.upload(
            "holder",
            call("first") +
                "\n" +
                """
                while (!context.getFiles().exists(FileScope.PIPELINE_SHARED, "go")) Thread.onSpinWait();
                String again;
                try { lemon.call(new OpenAiRequest("chat.completions", "{}")); again = "ok"; }
                catch (ResourceAccessException e) { again = e.getFailure().name(); }
                context.getFiles().writeText(FileScope.PIPELINE_SHARED, "second", again);
                """
                    .trimIndent(),
            declaration = declaration,
        )
    val next = h.upload("next", call("outcome"), declaration = declaration)
    val first = h.start(holder, "holder")
    await("the request at the service") { server.inFlight == 1 }
    val second = h.start(next, "next")
    h.await(second, RunState.WAITING_FOR_RESOURCES)
    server.script = null

    h.coordinator!!.forceRelease("lemon", first, admin)

    await("the service to see the connection go") { server.clientsGone == 1 }
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(second).state)
    Files.writeString(h.shared("holder", "go"), "x")
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(first).state)
    assertEquals("CANCELLED|null", h.result("holder", "first"))
    assertEquals("FORCE_RELEASED", h.result("holder", "second"))
    assertTrue(h.result("next", "outcome").startsWith("ok|200|"))
    assertEquals(2, server.requests.size, "the one that was cut and the next run's")
  }

  @Test
  fun `cancelling a run cuts the request in flight, the service sees the connection go, and the capacity is free`() {
    val h = harness(maxConcurrent = 2)
    h.defineOpenAi("lemon", settings(), capacity = 1)
    server.script = stuck
    // The failure of the call is not caught: a run that is cancelled ends when it gives up.
    val hash =
        h.upload(
            "caller",
            """context.getAccessors().openAiCompatible("lemon").call(new OpenAiRequest("chat.completions", "{}"));""",
            declaration = declaration,
        )
    val run = h.start(hash, "caller")
    await("the request at the service") { server.inFlight == 1 }

    assertEquals(CancelResult.CancellationRequested, h.service.cancel(run, Visibility.All))

    await("the service to see the connection go") { server.clientsGone == 1 }
    assertEquals(RunState.CANCELLED, h.awaitEnd(run).state)
    server.script = null
    assertEquals(emptyList(), h.coordinator!!.activity("lemon").holders)
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(h.start(hash, "caller")).state)
  }

  @Test
  fun `a request that a run's thread starts after the run has ended is refused and never sent`() {
    val h = harness()
    h.defineOpenAi("lemon", settings())
    val body =
        """
        final OpenAiAccessor lemon = context.getAccessors().openAiCompatible("lemon");
        final PipelineContext ctx = context;
        // What is not loaded yet cannot be loaded once the run's loader is closed.
        new ResourceAccessException("preload", ResourceFailure.FAILED, null);
        lemon.call(new OpenAiRequest("chat.completions", "{}"));
        ctx.getFiles().exists(FileScope.PIPELINE_SHARED, "go");
        ctx.getFiles().writeText(FileScope.PIPELINE_SHARED, "warm", "x");
        new Thread(() -> {
          while (!ctx.getFiles().exists(FileScope.PIPELINE_SHARED, "go")) Thread.onSpinWait();
          String result = "ok";
          try { lemon.call(new OpenAiRequest("chat.completions", "{}")); }
          catch (ResourceAccessException e) { result = e.getFailure().name(); }
          catch (Throwable t) { result = t.toString(); }
          ctx.getFiles().writeText(FileScope.PIPELINE_SHARED, "late", result);
        }).start();
        """
            .trimIndent()
    val hash = h.upload("straggler", body, declaration = declaration)

    assertEquals(RunState.SUCCEEDED, h.awaitEnd(h.start(hash, "straggler")).state)
    Files.writeString(h.shared("straggler", "go"), "x")
    h.awaitFile(h.shared("straggler", "late"))

    assertEquals("ENDED", h.result("straggler", "late"))
    assertEquals(1, server.requests.size, "only the call made while the run was running")
  }

  // ---- generations ----

  private fun replaceWith(file: Path, change: (Path) -> Unit) {
    val copy = file.resolveSibling(file.fileName.toString() + ".new")
    Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING)
    change(copy)
    Files.move(copy, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
  }

  private fun holdThenCall(): String = holdUntilReleased("started") + "\n" + call("outcome")

  @Test
  fun `a run keeps the key it was given while a later run gets the key of the reloaded keystore`() {
    val h = harness(maxConcurrent = 2)
    h.defineOpenAi("lemon", settings(), capacity = 2, alias = "lemon-key")
    val hash = h.upload("caller", holdThenCall(), declaration = declaration)
    val first = h.start(hash, "caller")
    h.awaitFile(h.shared("caller", "started"))

    replaceWith(keystore) {
      keystores.deleteEntry(it, "lemon-key", passwordFile)
      keystores.importSecret(it, "lemon-key", "sk-rotated-0123456789", passwordFile)
    }
    secrets.reload()
    Files.delete(h.shared("caller", "started"))
    val second = h.start(hash, "caller")
    h.awaitFile(h.shared("caller", "started"))
    Files.writeString(h.shared("caller", "release"), "x")

    assertEquals(RunState.SUCCEEDED, h.awaitEnd(first).state)
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(second).state)
    assertEquals(
        setOf("Bearer $key", "Bearer sk-rotated-0123456789"),
        server.requests.map { it.header("authorization") }.toSet(),
    )
    assertEquals(2, server.requests.size)
  }

  @Test
  fun `a run keeps the address it was given while a later run gets the changed settings`() {
    val h = harness(maxConcurrent = 2)
    val elsewhere = FakeOpenAiServer().also { extras += it }
    h.defineOpenAi("lemon", settings(), capacity = 2)
    val hash = h.upload("caller", holdThenCall(), declaration = declaration)
    val first = h.start(hash, "caller")
    h.awaitFile(h.shared("caller", "started"))

    val changed = Json.parseToJsonElement(settings(elsewhere.baseUrl)) as JsonObject
    h.resourceAdmin.update("lemon", null, null, admin, settings = changed)
    Files.delete(h.shared("caller", "started"))
    val second = h.start(hash, "caller")
    h.awaitFile(h.shared("caller", "started"))
    Files.writeString(h.shared("caller", "release"), "x")

    assertEquals(RunState.SUCCEEDED, h.awaitEnd(first).state)
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(second).state)
    assertEquals(1, server.requests.size, "the first run's request went where it was told")
    assertEquals(1, elsewhere.requests.size, "the second run's went to the new address")
  }

  // ---- streams (WI-47) ----

  /** A service that sends one event and then goes on without ending the stream. */
  private val endlessStream:
      (
          dev.lawlan.runline.accessors.fake.FakeRequest,
          dev.lawlan.runline.accessors.fake.FakeResponse,
      ) -> Boolean =
      { _, response ->
        response.beginChunked(200, mapOf("Content-Type" to "text/event-stream"))
        response.event("{}")
        response.hang()
        true
      }

  /** Pulls a stream until the run is stopped, noting what the pull came to. */
  private val pulling =
      """
      OpenAiAccessor lemon = context.getAccessors().openAiCompatible("lemon");
      OpenAiStream s = lemon.stream(new OpenAiRequest("chat.completions", "{}"));
      context.getFiles().writeText(FileScope.PIPELINE_SHARED, "first", s.next());
      String ended;
      try { s.next(); ended = "returned"; } catch (ResourceAccessException e) { ended = e.getFailure().name(); }
      context.getFiles().writeText(FileScope.PIPELINE_SHARED, "pull", ended);
      """
          .trimIndent()

  @Test
  fun `with capacity one a streaming run is the only request the service has, and the next run streams after it`() {
    val h = harness(maxConcurrent = 2)
    h.defineOpenAi("lemon", settings(), capacity = 1)
    server.chunkDelayMillis = 150
    val hash =
        h.upload(
            "streamer",
            """
            try (OpenAiStream s = context.getAccessors().openAiCompatible("lemon").stream(new OpenAiRequest("chat.completions", "{}"))) {
              int n = 0;
              while (s.next() != null) n++;
              context.getFiles().writeText(FileScope.PIPELINE_SHARED, "events", Integer.toString(n));
            }
            """
                .trimIndent(),
            declaration = declaration,
        )

    val first = h.start(hash, "streamer")
    await("the stream at the service") { server.inFlight == 1 }
    val second = h.start(hash, "streamer")
    h.await(second, RunState.WAITING_FOR_RESOURCES)

    assertEquals(RunState.SUCCEEDED, h.awaitEnd(first, seconds = 60).state)
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(second, seconds = 60).state)
    assertEquals(1, server.peakInFlight)
    assertEquals("6", h.result("streamer", "events"))
  }

  @Test
  fun `a forced release cuts the stream a run is pulling, the service sees the connection go, and the pull is cancelled`() {
    val h = harness(maxConcurrent = 2)
    h.defineOpenAi("lemon", settings(), capacity = 1)
    server.script = endlessStream
    val hash = h.upload("puller", pulling, declaration = declaration)
    val first = h.start(hash, "puller")
    await("the stream at the service") { server.inFlight == 1 }
    await("the first event") { Files.exists(h.shared("puller", "first")) }

    h.coordinator!!.forceRelease("lemon", first, admin)

    await("the service to see the connection go") { server.clientsGone == 1 }
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(first).state)
    assertEquals("{}", h.result("puller", "first"))
    assertEquals("CANCELLED", h.result("puller", "pull"))
    assertEquals(0, server.inFlight)
  }

  @Test
  fun `cancelling a run that pulls a stream cuts the stream and frees the capacity`() {
    val h = harness(maxConcurrent = 2)
    h.defineOpenAi("lemon", settings(), capacity = 1)
    server.script = endlessStream
    // The failure of the pull is not caught: a run that is cancelled ends when it gives up.
    val hash =
        h.upload(
            "puller",
            """
            OpenAiStream s = context.getAccessors().openAiCompatible("lemon").stream(new OpenAiRequest("chat.completions", "{}"));
            while (true) s.next();
            """
                .trimIndent(),
            declaration = declaration,
        )
    val run = h.start(hash, "puller")
    await("the stream at the service") { server.inFlight == 1 }

    assertEquals(CancelResult.CancellationRequested, h.service.cancel(run, Visibility.All))

    await("the service to see the connection go") { server.clientsGone == 1 }
    assertEquals(RunState.CANCELLED, h.awaitEnd(run).state)
    assertEquals(emptyList(), h.coordinator!!.activity("lemon").holders)
  }

  // ---- uploads and downloads (WI-53) ----

  /** A pipeline that writes a file of [megabytes] MiB into its shared directory and uploads it. */
  private fun uploading(megabytes: Int) =
      """
      OpenAiAccessor lemon = context.getAccessors().openAiCompatible("lemon");
      context.getFiles().writeText(FileScope.PIPELINE_SHARED, "big.txt", "x".repeat($megabytes * 1024 * 1024));
      String result;
      try {
        lemon.call(new OpenAiRequest("files.create", null, java.util.Collections.<String,String>emptyMap(), java.util.Collections.<String,String>emptyMap(), new OpenAiTimeouts(),
            java.util.Collections.singletonMap("purpose", "batch"),
            java.util.Collections.singletonList(OpenAiUpload.file("file", new OpenAiFile(FileScope.PIPELINE_SHARED, "big.txt"))),
            new OpenAiSizes()));
        result = "ok";
      } catch (ResourceAccessException e) { result = e.getFailure().name(); }
      context.getFiles().writeText(FileScope.PIPELINE_SHARED, "result", result);
      """
          .trimIndent()

  private val uploadSettings
    get() = settings(extra = """"endpoints":["files.create","audio.speech","chat.completions"]""")

  @Test
  fun `a forced release cuts an upload that is going out, the service sees the client leave, and the next waiter gets the service`() {
    val h = harness(maxConcurrent = 2, maxBytesPerScope = 64L * 1024 * 1024)
    h.defineOpenAi("lemon", uploadSettings, capacity = 1)
    server.uploadChunkDelayMillis = 20
    val holder = h.upload("holder", uploading(20), declaration = declaration)
    val next = h.upload("next", call("outcome"), declaration = declaration)
    val first = h.start(holder, "holder")
    await("the service to be receiving the file") { server.bytesReceived > 64 * 1024 }
    val second = h.start(next, "next")
    h.await(second, RunState.WAITING_FOR_RESOURCES)

    h.coordinator!!.forceRelease("lemon", first, admin)
    server.uploadChunkDelayMillis = 0

    await("the service to see the client leave") { server.clientsGone == 1 }
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(second, seconds = 60).state)
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(first, seconds = 60).state)
    assertEquals("CANCELLED", h.result("holder", "result"))
    assertEquals(
        1,
        server.requests.size,
        "the form never arrived whole; the one request is the next run's",
    )
  }

  @Test
  fun `cancelling a run that is downloading to a file cuts the download, no part of the file stays, and the capacity is free`() {
    val h = harness(maxConcurrent = 2)
    h.defineOpenAi("lemon", uploadSettings, capacity = 1)
    server.script = { _, response ->
      response.beginChunked(200, mapOf("Content-Type" to "audio/mpeg"))
      response.chunk(ByteArray(4096) { 1 })
      response.hang()
      true
    }
    // The failure of the call is not caught: a run that is cancelled ends when it gives up.
    val hash =
        h.upload(
            "caller",
            """context.getAccessors().openAiCompatible("lemon").downloadTo(new OpenAiRequest("audio.speech", "{}"), new OpenAiFile(FileScope.PIPELINE_SHARED, "a.mp3"));""",
            declaration = declaration,
        )
    val run = h.start(hash, "caller")
    await("the answer to have begun") { server.requests.size == 1 }
    Thread.sleep(300)

    assertEquals(CancelResult.CancellationRequested, h.service.cancel(run, Visibility.All))

    await("the service to see the client leave") { server.clientsGone == 1 }
    assertEquals(RunState.CANCELLED, h.awaitEnd(run).state)
    assertEquals(emptyList(), h.coordinator!!.activity("lemon").holders)
    assertEquals(emptyList(), Files.list(h.shared("caller", "x").parent).use { it.toList() })
  }

  @Test
  fun `what is downloaded to a directory counts for what the directory may hold, and what does not fit is not kept`() {
    val h = harness(maxConcurrent = 2, maxBytesPerScope = 3000)
    h.defineOpenAi("lemon", uploadSettings, capacity = 1)
    val hash =
        h.upload(
            "caller",
            """
            OpenAiAccessor lemon = context.getAccessors().openAiCompatible("lemon");
            String result;
            try {
              lemon.downloadTo(new OpenAiRequest("audio.speech", "{}"), new OpenAiFile(FileScope.PIPELINE_SHARED, "a.mp3"));
              result = "ok";
            } catch (ResourceAccessException e) { result = e.getFailure().name(); }
            context.getFiles().writeText(FileScope.PIPELINE_SHARED, "result", result);
            """
                .trimIndent(),
            declaration = declaration,
        )

    assertEquals(RunState.SUCCEEDED, h.awaitEnd(h.start(hash, "caller")).state)

    assertEquals("SCOPE_FULL", h.result("caller", "result"))
    assertEquals(
        listOf("result"),
        Files.list(h.shared("caller", "x").parent).use { l ->
          l.map { it.fileName.toString() }.toList()
        },
    )
  }
}
