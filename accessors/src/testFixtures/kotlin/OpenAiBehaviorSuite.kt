package dev.lawlan.runline.accessors.suite

import dev.lawlan.runline.accessors.fake.FakeOpenAiServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The behavior of `openai-compatible` accessors every host must show, the same for the Engine and
 * the development entry (WI-46): how the resource's rules merge, lock and bound a pipeline's
 * request, what a pipeline is told when the service refuses, fails, redirects or is too slow, what
 * goes out with the request and what comes back with the answer, and what is recorded. The service
 * is the Fake on a real socket, the pipelines are compiled for real.
 */
abstract class OpenAiBehaviorSuite {
  protected abstract fun newRig(): AccessorRig

  private val rig: AccessorRig by lazy { newRig() }
  private val server = FakeOpenAiServer()
  private val key = "sk-suite-0123456789abcdef"
  private val typed = mapOf("lemon" to "openai-compatible")

  @AfterTest
  fun close() {
    rig.close()
    server.close()
  }

  private fun settings(extra: String = "") =
      """{"baseUrl":"${server.baseUrl}"${if (extra.isEmpty()) "" else ",$extra"}}"""

  private fun java(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  /** One call written so that its outcome, as `label=category|status`, is added to `all`. */
  private fun attempt(
      label: String,
      endpoint: String = "chat.completions",
      body: String? = "{}",
      path: String = "java.util.Collections.<String,String>emptyMap()",
      timeouts: String = "new OpenAiTimeouts()",
  ) =
      """
      try {
        OpenAiResponse r = lemon.call(new OpenAiRequest(${java(endpoint)}, ${body?.let(::java) ?: "null"}, $path, java.util.Collections.<String,String>emptyMap(), $timeouts));
        all.append("$label=ok|").append(r.getStatus()).append(";");
      } catch (ResourceAccessException e) {
        all.append("$label=").append(e.getFailure().name()).append("|").append(e.getStatus()).append(";");
      }
      """
          .trimIndent()

  /** A pipeline that makes the [attempts] in turn and writes what each came to to `all`. */
  private fun attempts(vararg attempts: String) =
      """
      OpenAiAccessor lemon = context.getAccessors().openAiCompatible("lemon");
      StringBuilder all = new StringBuilder();
      ${attempts.joinToString("\n")}
      context.getFiles().writeText(FileScope.PIPELINE_SHARED, "all", all.toString());
      """
          .trimIndent()

  @Test
  fun `a call merges the resource's defaults under the pipeline's values and sends the key and the headers of the resource`() {
    rig.defineOpenAi(
        "lemon",
        settings(
            """"defaults":{"model":"m1","temperature":0.2,"max_tokens":50},"organization":"org-7","headers":{"X-Team":"blue"}"""
        ),
        RigKey.Value(key),
    )

    val outcome =
        rig.run(
            attempts(attempt("a", body = """{"messages":[],"temperature":0.9}""")),
            typed = typed,
        )

    assertTrue(outcome.succeeded, outcome.failure)
    assertEquals("a=ok|200;", outcome.shared("all"))
    val seen = server.requests.single()
    assertEquals("Bearer $key", seen.header("authorization"))
    assertEquals("org-7", seen.header("openai-organization"))
    assertEquals("blue", seen.header("x-team"))
    assertEquals("POST", seen.method)
    assertEquals("/v1/chat/completions", seen.path)
    assertTrue(seen.body.contains("\"model\":\"m1\""), seen.body)
    assertTrue(seen.body.contains("\"max_tokens\":50"), seen.body)
    assertTrue(seen.body.contains("\"temperature\":0.9"), seen.body)
  }

  @Test
  fun `a service that needs no key gets no authorization`() {
    rig.defineOpenAi("lemon", settings())

    val outcome = rig.run(attempts(attempt("a")), typed = typed)

    assertTrue(outcome.succeeded, outcome.failure)
    assertNull(server.requests.single().header("authorization"))
  }

  @Test
  fun `what the resource's rules refuse is refused with its category and never reaches the service`() {
    rig.defineOpenAi(
        "lemon",
        settings(
            """"lockedParameters":["temperature"],"defaults":{"temperature":0.5},"allowedModels":["m1"],"maxValues":{"max_tokens":100}"""
        ),
    )

    val outcome =
        rig.run(
            attempts(
                attempt("locked", body = """{"model":"m1","temperature":0.9}"""),
                attempt("model", body = """{"model":"m2"}"""),
                attempt("ceiling", body = """{"model":"m1","max_tokens":101}"""),
                attempt("stream", body = """{"model":"m1","stream":true}"""),
                attempt("disabled", endpoint = "files.list", body = null),
                attempt("unknown", endpoint = "chat/completions"),
                attempt("body", body = "not json"),
                attempt(
                    "path",
                    endpoint = "models.retrieve",
                    body = null,
                    path = "java.util.Collections.singletonMap(\"model\", \"../x\")",
                ),
            ),
            typed = typed,
        )

    assertTrue(outcome.succeeded, outcome.failure)
    assertEquals(
        "locked=PARAMETER_LOCKED|null;model=MODEL_NOT_ALLOWED|null;ceiling=VALUE_ABOVE_LIMIT|null;" +
            "stream=STREAM_NOT_SUPPORTED|null;disabled=ENDPOINT_NOT_ENABLED|null;" +
            "unknown=UNKNOWN_ENDPOINT|null;body=INVALID_ARGUMENT|null;path=INVALID_ARGUMENT|null;",
        outcome.shared("all"),
    )
    assertEquals(0, server.requests.size)
  }

  @Test
  fun `what the service answers is a category with the status, and the pipeline is told no more`() {
    rig.defineOpenAi("lemon", settings(), RigKey.Value(key))
    val statuses = listOf(401, 403, 429, 500, 503, 400, 404, 422)
    server.script = { request, response ->
      val status = request.body.substringAfter("\"status\":").substringBefore('}').trim().toInt()
      response.json(status, """{"error":{"message":"echo $key"}}""", mapOf("X-Echo" to key))
      true
    }

    val outcome =
        rig.run(
            attempts(*statuses.map { attempt("s$it", body = """{"status":$it}""") }.toTypedArray()),
            typed = typed,
        )

    assertTrue(outcome.succeeded, outcome.failure)
    assertEquals(
        "s401=DENIED|401;s403=DENIED|403;s429=RATE_LIMITED|429;s500=SERVER_ERROR|500;" +
            "s503=SERVER_ERROR|503;s400=REQUEST_REJECTED|400;s404=REQUEST_REJECTED|404;" +
            "s422=REQUEST_REJECTED|422;",
        outcome.shared("all"),
    )
    assertFalse(outcome.shared("all")!!.contains(key))
  }

  @Test
  fun `a redirect out of the base address is not followed and the other place hears nothing`() {
    val elsewhere = FakeOpenAiServer()
    try {
      rig.defineOpenAi("lemon", settings(), RigKey.Value(key))
      server.script = { _, response ->
        response.redirect(307, "${elsewhere.baseUrl}/chat/completions")
        true
      }

      val outcome = rig.run(attempts(attempt("a")), typed = typed)

      assertTrue(outcome.succeeded, outcome.failure)
      assertEquals("a=REDIRECT_BLOCKED|307;", outcome.shared("all"))
      assertEquals(0, elsewhere.requests.size)
    } finally {
      elsewhere.close()
    }
  }

  @Test
  fun `a key that the host does not have fails the call and sends nothing`() {
    rig.defineOpenAi("lemon", settings(), RigKey.Missing)

    val outcome = rig.run(attempts(attempt("a")), typed = typed)

    assertTrue(outcome.succeeded, outcome.failure)
    assertEquals("a=SECRET_UNAVAILABLE|null;", outcome.shared("all"))
    assertEquals(0, server.requests.size)
  }

  @Test
  fun `the headers that come back have no credential and no echo of the key`() {
    rig.defineOpenAi("lemon", settings(), RigKey.Value(key))
    server.script = { request, response ->
      response.json(
          200,
          "{}",
          mapOf(
              "Set-Cookie" to "s=1",
              "WWW-Authenticate" to "Bearer",
              "X-Api-Key" to key,
              "X-Debug" to "you sent ${request.header("authorization")}",
              "X-Request-Id" to "req-1",
          ),
      )
      true
    }
    val body =
        """
        OpenAiAccessor lemon = context.getAccessors().openAiCompatible("lemon");
        OpenAiResponse r = lemon.call(new OpenAiRequest("chat.completions", "{}"));
        context.getFiles().writeText(FileScope.PIPELINE_SHARED, "headers", r.getHeaders().toString());
        """
            .trimIndent()

    val outcome = rig.run(body, typed = typed)

    assertTrue(outcome.succeeded, outcome.failure)
    val headers = outcome.shared("headers")!!
    assertFalse(headers.contains(key), headers)
    assertTrue(headers.contains("x-debug=[you sent Bearer ***]"), headers)
    assertTrue(headers.contains("x-request-id=[req-1]"), headers)
    assertFalse(headers.contains("set-cookie") || headers.contains("x-api-key"), headers)
  }

  @Test
  fun `a pipeline can shorten the time a call may take and the call ends with its own category`() {
    rig.defineOpenAi("lemon", settings())
    server.responseDelayMillis = 3000

    val outcome =
        rig.run(
            attempts(
                attempt(
                    "slow",
                    timeouts =
                        "new OpenAiTimeouts(null, java.time.Duration.ofMillis(300), null, null, null)",
                )
            ),
            typed = typed,
        )

    assertTrue(outcome.succeeded, outcome.failure)
    assertEquals("slow=FIRST_BYTE_TIMEOUT|null;", outcome.shared("all"))
  }

  @Test
  fun `what a recording host records about the resource is the name, the type and the kind of action`() {
    rig.defineOpenAi("lemon", settings(), RigKey.Value(key))

    val outcome =
        rig.run(
            attempts(attempt("a", body = """{"messages":[{"content":"prompt-marker-xyz"}]}""")),
            typed = typed,
        )

    assertTrue(outcome.succeeded, outcome.failure)
    if (rig.records) {
      val recorded = assertNotNull(outcome.recorded)
      assertTrue(recorded.contains("lemon") && recorded.contains("openai-compatible"), recorded)
      assertFalse(recorded.contains("prompt-marker-xyz") || recorded.contains(key), recorded)
      assertFalse(recorded.contains("chat.completions") || recorded.contains("127.0.0.1"), recorded)
    } else {
      assertNull(outcome.recorded, "only the development entry records")
    }
  }

  /** A pipeline that opens a stream of the chat endpoint, pulls what [pull] says and records it. */
  private fun streaming(
      pull: String,
      request: String = "new OpenAiRequest(\"chat.completions\", \"{}\")",
  ) =
      """
      OpenAiAccessor lemon = context.getAccessors().openAiCompatible("lemon");
      StringBuilder all = new StringBuilder();
      try {
        $pull
      } catch (ResourceAccessException e) {
        all.append("failed=").append(e.getFailure().name()).append(";");
      }
      context.getFiles().writeText(FileScope.PIPELINE_SHARED, "all", all.toString());
      """
          .trimIndent()
          .replace("REQUEST", request)

  @Test
  fun `a pipeline pulls a streamed answer event by event, to its end, with JDK types only`() {
    rig.defineOpenAi("lemon", settings(), RigKey.Value(key))
    val body =
        streaming(
            """
            try (OpenAiStream s = lemon.stream(REQUEST)) {
              all.append("status=").append(s.getStatus()).append(";");
              String data;
              int n = 0;
              while ((data = s.next()) != null) n++;
              all.append("events=").append(n).append(";");
            }
            """
        )

    val outcome = rig.run(body, typed = typed)

    assertTrue(outcome.succeeded, outcome.failure)
    assertEquals("status=200;events=6;", outcome.shared("all"))
    val seen = server.requests.single()
    assertEquals("Bearer $key", seen.header("authorization"))
    assertEquals("text/event-stream", seen.header("accept"))
    assertTrue(seen.body.contains("\"stream\":true"), seen.body)
    assertEquals(0, server.inFlight)
  }

  /** A service that sends one event and then goes on without ending the stream. */
  private fun endless() {
    server.script = { _, response ->
      response.beginChunked(200, mapOf("Content-Type" to "text/event-stream"))
      response.event("{}")
      response.hang()
      true
    }
  }

  private fun await(what: String, condition: () -> Boolean) {
    val deadline = System.nanoTime() + 10_000_000_000
    while (!condition()) {
      check(System.nanoTime() < deadline) { "gave up waiting for $what" }
      Thread.sleep(10)
    }
  }

  @Test
  fun `a stream that the resource's rules refuse is refused with its category and nothing is sent`() {
    rig.defineOpenAi("lemon", settings("\"endpoints\":[\"chat.completions\",\"embeddings\"]"))
    val body =
        streaming(
            """
            try { lemon.stream(new OpenAiRequest("embeddings", "{}")); } catch (ResourceAccessException e) { all.append("embeddings=").append(e.getFailure().name()).append(";"); }
            try { lemon.stream(new OpenAiRequest("chat.completions", "{\"stream\":true}")); } catch (ResourceAccessException e) { all.append("own=").append(e.getFailure().name()).append(";"); }
            try { lemon.stream(new OpenAiRequest("files.list", null)); } catch (ResourceAccessException e) { all.append("disabled=").append(e.getFailure().name()).append(";"); }
            """
        )

    val outcome = rig.run(body, typed = typed)

    assertTrue(outcome.succeeded, outcome.failure)
    assertEquals(
        "embeddings=STREAM_NOT_SUPPORTED;own=INVALID_ARGUMENT;disabled=ENDPOINT_NOT_ENABLED;",
        outcome.shared("all"),
    )
    assertEquals(0, server.requests.size)
  }

  @Test
  fun `a stream holds the run's share, so a second request waits for it and gets the quota category`() {
    rig.defineOpenAi("lemon", settings())
    endless()
    val body =
        streaming(
            """
            try (OpenAiStream s = lemon.stream(REQUEST)) {
              s.next();
              try {
                lemon.call(new OpenAiRequest("chat.completions", "{}", java.util.Collections.<String,String>emptyMap(), java.util.Collections.<String,String>emptyMap(), new OpenAiTimeouts(null, null, null, null, java.time.Duration.ofMillis(200))));
                all.append("second=ok;");
              } catch (ResourceAccessException e) {
                all.append("second=").append(e.getFailure().name()).append(";");
              }
            }
            """
        )

    val outcome = rig.run(body, typed = typed)

    assertTrue(outcome.succeeded, outcome.failure)
    assertEquals("second=QUOTA_WAIT_TIMEOUT;", outcome.shared("all"))
    assertEquals(1, server.requests.size)
    await("the service to see the stream closed") { server.clientsGone == 1 }
  }

  @Test
  fun `a gap longer than the idle limit ends a stream with the idle category, and the events before it were pulled`() {
    rig.defineOpenAi("lemon", settings("\"timeouts\":{\"idleMs\":300}"))
    endless()
    val body =
        streaming(
            """
            try (OpenAiStream s = lemon.stream(REQUEST)) {
              all.append("first=").append(s.next()).append(";");
              s.next();
            }
            """
        )

    val outcome = rig.run(body, typed = typed)

    assertTrue(outcome.succeeded, outcome.failure)
    assertEquals("first={};failed=IDLE_TIMEOUT;", outcome.shared("all"))
    await("the service to see the connection go") { server.clientsGone == 1 }
  }

  @Test
  fun `a stream the pipeline leaves open is closed when the run ends`() {
    rig.defineOpenAi("lemon", settings())
    endless()
    val body =
        streaming(
            """
            OpenAiStream s = lemon.stream(REQUEST);
            all.append("first=").append(s.next()).append(";");
            """
        )

    val outcome = rig.run(body, typed = typed)

    assertTrue(outcome.succeeded, outcome.failure)
    await("the service to see the connection go") { server.clientsGone == 1 }
    assertEquals(0, server.inFlight)
  }

  private val none = "java.util.Collections.<String,String>emptyMap()"

  /**
   * A pipeline that writes a file into its shared directory, then does [body]; `all` is written.
   */
  private fun withFile(path: String, text: String, body: String) =
      """
      OpenAiAccessor lemon = context.getAccessors().openAiCompatible("lemon");
      StringBuilder all = new StringBuilder();
      context.getFiles().writeText(FileScope.PIPELINE_SHARED, ${java(path)}, ${java(text)});
      try {
        $body
      } catch (ResourceAccessException e) {
        all.append("failed=").append(e.getFailure().name()).append("|").append(e.getStatus()).append(";");
      }
      context.getFiles().writeText(FileScope.PIPELINE_SHARED, "all", all.toString());
      """
          .trimIndent()

  @Test
  fun `a pipeline uploads a file of its own directory, which goes out as the file part of one multipart form`() {
    rig.defineOpenAi("lemon", settings("\"endpoints\":[\"files.create\"]"), RigKey.Value(key))
    val body =
        withFile(
            "data.jsonl",
            "{\"custom_id\":\"1\"}",
            """
            OpenAiResponse r = lemon.call(new OpenAiRequest("files.create", null, $none, $none, new OpenAiTimeouts(),
                java.util.Collections.singletonMap("purpose", "batch"),
                java.util.Collections.singletonList(OpenAiUpload.file("file", new OpenAiFile(FileScope.PIPELINE_SHARED, "data.jsonl"))),
                new OpenAiSizes()));
            all.append("status=").append(r.getStatus()).append(";");
            """,
        )

    val outcome = rig.run(body, typed = typed)

    assertTrue(outcome.succeeded, outcome.failure)
    assertEquals("status=200;", outcome.shared("all"))
    val seen = server.requests.single()
    assertEquals("Bearer $key", seen.header("authorization"))
    assertEquals("batch", seen.field("purpose"))
    assertEquals("data.jsonl", seen.file("file")!!.filename)
    assertEquals("{\"custom_id\":\"1\"}", seen.file("file")!!.bytes.decodeToString())
  }

  @Test
  fun `a pipeline has the audio as bytes, as a file of either directory, and as chunks it pulls`() {
    rig.defineOpenAi("lemon", settings("\"endpoints\":[\"audio.speech\"]"), RigKey.Value(key))
    val body =
        """
        OpenAiAccessor lemon = context.getAccessors().openAiCompatible("lemon");
        StringBuilder all = new StringBuilder();
        OpenAiBinaryResponse mem = lemon.download(new OpenAiRequest("audio.speech", "{}"));
        all.append("mem=").append(mem.getBody().length).append(";");
        OpenAiStoredResponse stored = lemon.downloadTo(new OpenAiRequest("audio.speech", "{}"), new OpenAiFile(FileScope.PIPELINE_SHARED, "a.mp3"));
        all.append("shared=").append(stored.getPath()).append(":").append(stored.getSize()).append(";");
        OpenAiStoredResponse priv = lemon.downloadTo(new OpenAiRequest("audio.speech", "{}"), new OpenAiFile(FileScope.RUN_PRIVATE, "deep/b.mp3"));
        all.append("private=").append(priv.getPath()).append(":").append(priv.getSize()).append(";");
        long total = 0;
        try (OpenAiByteStream s = lemon.streamBytes(new OpenAiRequest("audio.speech", "{}"))) {
          byte[] chunk;
          while ((chunk = s.next()) != null) total += chunk.length;
        }
        all.append("streamed=").append(total).append(";");
        context.getFiles().writeText(FileScope.PIPELINE_SHARED, "all", all.toString());
        """
            .trimIndent()

    val outcome = rig.run(body, typed = typed)

    assertTrue(outcome.succeeded, outcome.failure)
    val size = FakeOpenAiServer.SPEECH.size
    assertEquals(
        "mem=$size;shared=a.mp3:$size;private=deep/b.mp3:$size;streamed=$size;",
        outcome.shared("all"),
    )
    assertContentEquals(FakeOpenAiServer.SPEECH, outcome.sharedBytes("a.mp3"))
    assertEquals(0, server.inFlight)
  }

  @Test
  fun `a pipeline that names a path outside its directories is refused for a source and for a target, and nothing leaves or enters`() {
    rig.defineOpenAi("lemon", settings("\"endpoints\":[\"files.create\",\"audio.speech\"]"))
    val attempts =
        listOf("../escape.txt", "/etc/passwd", "a/../../escape.txt", "")
            .mapIndexed { i, path ->
              """
              try {
                lemon.call(new OpenAiRequest("files.create", null, $none, $none, new OpenAiTimeouts(),
                    java.util.Collections.singletonMap("purpose", "batch"),
                    java.util.Collections.singletonList(OpenAiUpload.file("file", new OpenAiFile(FileScope.PIPELINE_SHARED, ${java(path)}), "x.txt")),
                    new OpenAiSizes()));
                all.append("up$i=ok;");
              } catch (ResourceAccessException e) { all.append("up$i=").append(e.getFailure().name()).append(";"); }
              try {
                lemon.downloadTo(new OpenAiRequest("audio.speech", "{}"), new OpenAiFile(FileScope.RUN_PRIVATE, ${java(path)}));
                all.append("down$i=ok;");
              } catch (ResourceAccessException e) { all.append("down$i=").append(e.getFailure().name()).append(";"); }
              """
            }
            .joinToString("\n")
    val body =
        """
        OpenAiAccessor lemon = context.getAccessors().openAiCompatible("lemon");
        StringBuilder all = new StringBuilder();
        $attempts
        context.getFiles().writeText(FileScope.PIPELINE_SHARED, "all", all.toString());
        """
            .trimIndent()

    val outcome = rig.run(body, typed = typed)

    assertTrue(outcome.succeeded, outcome.failure)
    assertEquals(
        (0..3).joinToString("") { "up$it=PATH_REJECTED;down$it=PATH_REJECTED;" },
        outcome.shared("all"),
    )
    assertEquals(0, server.requests.size, "nothing was uploaded and nothing was asked for")
    assertEquals(false, rig.resourceRoot.resolve("../escape.txt").normalize().toFile().exists())
  }
}
