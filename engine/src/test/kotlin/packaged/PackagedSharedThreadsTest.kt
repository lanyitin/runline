package dev.lawlan.runline.engine.packaged

import dev.lawlan.runline.accessors.fake.FakeOpenAiServer
import dev.lawlan.runline.accessors.jdbc.RealPostgres
import dev.lawlan.runline.accessors.support.TestDirectories
import dev.lawlan.runline.engine.support.Keystores
import dev.lawlan.runline.engine.support.OtelCollector
import dev.lawlan.runline.engine.support.PackagedEngine
import dev.lawlan.runline.engine.support.TestTimeouts
import dev.lawlan.runline.engine.support.awaitCondition
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.serialization.json.*

/**
 * The background work the Engine shares between runs holds no run's class loader (WI-66, ADR-001
 * "共用背景執行緒"): on the packaged Engine, the first run that sets a kind of shared work going is
 * followed to its end, and then the class loader of every run is reclaimed once the collector of
 * the JVM runs. The Engine is a new process in every test, so the run is really the first.
 */
class PackagedSharedThreadsTest {
  private val work: Path = TestDirectories.forThisTest("packaged-shared-threads")
  private val engine = PackagedEngine(work)
  private val closeable = mutableListOf<AutoCloseable>()

  @AfterTest
  fun close() {
    engine.close()
    closeable.reversed().forEach { runCatching { it.close() } }
  }

  private val jcmd = Path.of(System.getProperty("runline.java")).resolveSibling("jcmd").toString()

  /** What `jcmd` says of the Engine process. */
  private fun jcmd(command: String): String {
    val process =
        ProcessBuilder(jcmd, engine.engine.process.pid().toString(), command)
            .redirectErrorStream(true)
            .start()
    val text = process.inputStream.bufferedReader().readText()
    assertTrue(process.waitFor(30, TimeUnit.SECONDS), "jcmd $command did not end")
    return text
  }

  /**
   * How many class loaders of runs are still there after a full collection (the class histogram
   * makes one before it counts).
   */
  private fun runLoadersLeft(): Int =
      Regex(
              """^\s*\d+:\s+(\d+)\s+\d+\s+dev\.lawlan\.runline\.runner\.RunClassLoader\s""",
              RegexOption.MULTILINE,
          )
          .find(jcmd("GC.class_histogram"))
          ?.groupValues
          ?.get(1)
          ?.toInt() ?: 0

  /**
   * The class loader of every run (all of them have ended) is reclaimed; when it is not, says which
   * threads the Engine has.
   */
  private fun assertEveryRunLoaderReclaimed() {
    awaitCondition(
        "the class loader of every run to be reclaimed",
        diagnostics = {
          "left: ${runLoadersLeft()}\n" +
              engine
                  .engineOutput()
                  .lines()
                  .filter { "left threads running" in it }
                  .joinToString("\n") +
              "\nthreads: " +
              jcmd("Thread.print")
                  .lines()
                  .filter { it.startsWith("\"") }
                  .map { it.removePrefix("\"").substringBefore('"') }
        },
    ) {
      runLoadersLeft() == 0
    }
  }

  private fun startWith(extra: Map<String, String> = emptyMap()) {
    engine.migrate()
    engine.start(extra)
  }

  /** The Fake service as the `openai-compatible` resource `llm` (no key), capacity one. */
  private fun defineLlm(): FakeOpenAiServer {
    val service = FakeOpenAiServer().also { closeable += it }
    engine.expect(
        201,
        "POST",
        "/api/v1/resources",
        """{"name":"llm","capacity":1,"type":"openai-compatible",""" +
            """"settings":{"baseUrl":"${service.baseUrl}","endpoints":["chat.completions"]}}""",
    )
    return service
  }

  private fun assertSucceeded(run: JsonObject) =
      assertEquals("SUCCEEDED", run["state"]!!.jsonPrimitive.content, "$run\n${engine.tail()}")

  @Test
  fun `the first run that calls an openai-compatible resource is reclaimed`() {
    startWith()
    defineLlm()

    val run =
        engine.runToEnd(
            "caller",
            PackagedEngine.typed("llm" to "openai-compatible"),
            """
            context.getAccessors().openAiCompatible("llm")
                .call(new OpenAiRequest("chat.completions", "{\"model\":\"m\"}"));
            """
                .trimIndent(),
        )

    assertSucceeded(run)
    assertEveryRunLoaderReclaimed()
  }

  @Test
  fun `the first run that calls an openai-compatible resource from a thread of its own, in a group of its own and with a value that threads inherit, is reclaimed`() {
    startWith()
    defineLlm()

    // What a new thread takes from the thread that makes it: the context class loader, the group
    // and the inheritable thread-local values; on the pipeline's thread all three are the run's.
    // The run's own thread is left alone: it is not the one that makes the shared thread here.
    val run =
        engine.runToEnd(
            "own-thread",
            PackagedEngine.typed("llm" to "openai-compatible"),
            """
            ThreadGroup group = new ThreadGroup("pipeline-own") {};
            Throwable[] failed = new Throwable[1];
            Thread worker = new Thread(group, () -> {
              try {
                new InheritableThreadLocal<Object>() {}.set(new Object() {});
                context.getAccessors().openAiCompatible("llm")
                    .call(new OpenAiRequest("chat.completions", "{\"model\":\"m\"}"));
              } catch (Throwable t) { failed[0] = t; }
            });
            worker.start();
            try { worker.join(); } catch (InterruptedException e) { throw new RuntimeException(e); }
            if (failed[0] != null) throw new RuntimeException(failed[0]);
            """
                .trimIndent(),
        )

    assertSucceeded(run)
    assertEveryRunLoaderReclaimed()
  }

  @Test
  fun `the first run whose time is limited is reclaimed`() {
    startWith(mapOf("RUNLINE_RUN_TIMEOUT_SECONDS" to "600"))

    val run = engine.runToEnd("limited", PackagedEngine.FILES, """System.out.println("done");""")

    assertSucceeded(run)
    assertEveryRunLoaderReclaimed()
  }

  @Test
  fun `the first run that streams from an openai-compatible resource is reclaimed`() {
    startWith()
    defineLlm()

    val run =
        engine.runToEnd(
            "streamer",
            PackagedEngine.typed("llm" to "openai-compatible"),
            """
            try (OpenAiStream stream = context.getAccessors().openAiCompatible("llm")
                .stream(new OpenAiRequest("chat.completions", "{\"model\":\"m\"}"))) {
              int events = 0;
              while (stream.next() != null) events++;
              if (events == 0) throw new IllegalStateException("no event");
            }
            """
                .trimIndent(),
        )

    assertSucceeded(run)
    assertEveryRunLoaderReclaimed()
  }

  @Test
  fun `the first run that waits for a resource, and the run it waits for, are reclaimed`() {
    startWith()
    engine.expect(201, "POST", "/api/v1/resources", """{"name":"lock","capacity":1}""")
    val go = work.resolve("go")
    val declaration = PackagedEngine.FILES + ", resources = {\"lock\"}"
    val holder =
        engine.upload(
            "holder",
            declaration,
            """
            java.nio.file.Path go = java.nio.file.Path.of(${JsonPrimitive(go.toString())});
            long until = System.nanoTime() + 60_000_000_000L;
            while (!java.nio.file.Files.exists(go) && System.nanoTime() < until) {
              try { Thread.sleep(50); } catch (InterruptedException e) { throw new RuntimeException(e); }
            }
            """
                .trimIndent(),
        )
    val waiter = engine.upload("waiter", declaration, """System.out.println("had it");""")

    // The holder keeps the resource until the test lets it go; meanwhile the waiter waits for it,
    // which sets the timer of its wait going; the release of each sets the release wait going.
    val held = engine.startRun(holder, "holder")
    engine.awaitState(held, "RUNNING")
    val waiting = engine.startRun(waiter, "waiter")
    engine.awaitState(waiting, "WAITING_FOR_RESOURCES")
    Files.writeString(go, "go")

    assertSucceeded(engine.awaitEnd(held))
    assertSucceeded(engine.awaitEnd(waiting))
    assertEveryRunLoaderReclaimed()
  }

  @Test
  fun `the first run that a cron trigger starts is reclaimed`() {
    startWith()
    val hash = engine.upload("ticking", PackagedEngine.FILES, """System.out.println("tick");""")
    engine.expect(
        201,
        "POST",
        "/api/v1/triggers",
        """{"name":"tick","kind":"cron","contentHash":"$hash","pipeline":"ticking",""" +
            """"cron":"* * * * *"}""",
    )

    // The trigger fires at the next whole minute.
    var started: String? = null
    awaitCondition(
        "the trigger to start a run",
        timeout = TestTimeouts.condition.multipliedBy(2),
        diagnostics = { engine.tail() },
    ) {
      Thread.sleep(500)
      started =
          engine
              .json(engine.call("GET", "/api/v1/triggers/tick/firings"))["firings"]!!
              .jsonArray
              .firstOrNull()
              ?.jsonObject
              ?.get("runId")
              ?.jsonPrimitive
              ?.contentOrNull
      started != null
    }
    engine.expect(204, "DELETE", "/api/v1/triggers/tick")

    assertSucceeded(engine.awaitEnd(started!!))
    assertEveryRunLoaderReclaimed()
  }

  @Test
  fun `the first run that uses a jdbc-pool resource, whose pool outlives it, is reclaimed`() {
    val target = RealPostgres.newDatabase().also { closeable += it }
    val role = "wi66_" + UUID.randomUUID().toString().replace("-", "").take(10)
    target.createRole(role, "wi66-db-pass")
    val keystores = Keystores(Files.createDirectories(work.resolve("keystore")))
    val keystore = keystores.pkcs12("engine.p12", mapOf("db-pw" to "wi66-db-pass"), "wi66-pass")
    startWith(
        mapOf(
            "RUNLINE_KEYSTORE_PATH" to keystore.toString(),
            "RUNLINE_KEYSTORE_PASSWORD_FILE" to
                keystores.passwordFile("wi66-pass", "engine.pw").toString(),
        )
    )
    engine.expect(
        201,
        "POST",
        "/api/v1/resources",
        """{"name":"db","capacity":1,"type":"jdbc-pool","secretAlias":"db-pw",""" +
            """"settings":{"kind":"postgresql","host":"${RealPostgres.host}",""" +
            """"port":${RealPostgres.port},"database":"${target.name}","username":"$role"}}""",
    )

    val run =
        engine.runToEnd(
            "querying",
            PackagedEngine.typed("db" to "jdbc-pool"),
            """context.getAccessors().jdbcPool("db").query("SELECT 1");""",
        )

    assertSucceeded(run)
    // The pool keeps the connection the run made, for the next run; a check uses the database too.
    assertEquals(1, target.sessions(user = role))
    engine.expect(200, "POST", "/api/v1/resources/db/check")
    assertEveryRunLoaderReclaimed()
  }

  @Test
  fun `the first run while traces and metrics are exported is reclaimed`() {
    val collector = OtelCollector().also { closeable += it }
    startWith(
        mapOf(
            "OTEL_TRACES_EXPORTER" to "otlp",
            "OTEL_METRICS_EXPORTER" to "otlp",
            "OTEL_EXPORTER_OTLP_PROTOCOL" to "http/protobuf",
            "OTEL_EXPORTER_OTLP_ENDPOINT" to collector.endpoint,
            "OTEL_METRIC_EXPORT_INTERVAL" to "500",
            "OTEL_BSP_SCHEDULE_DELAY" to "200",
        )
    )
    engine.expect(201, "POST", "/api/v1/resources", """{"name":"lock","capacity":1}""")

    val run =
        engine.runToEnd(
            "observed",
            PackagedEngine.FILES + ", resources = {\"lock\"}",
            """System.out.println("observed");""",
        )

    assertSucceeded(run)
    awaitCondition("the run's metrics to be exported", diagnostics = { collector.logs }) {
      collector.metrics().any { it.name == "runline.runner.classloaders.created" }
    }
    assertEveryRunLoaderReclaimed()
  }
}
