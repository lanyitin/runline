package dev.lawlan.runline.runner

import dev.lawlan.runline.core.FileScope
import dev.lawlan.runline.runner.support.TestJars
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class RunnerTest {
  @TempDir lateinit var tmp: Path

  private val workspaceEvents = CopyOnWriteArrayList<WorkspaceEvent>()
  private val runners = mutableListOf<Runner>()

  private fun workspaces(retention: Duration = Duration.ofHours(1)) =
      Workspaces(
          WorkspaceConfig(
              sharedRoot = tmp.resolve("persistent"),
              runRoot = tmp.resolve("scratch"),
              maxBytesPerScope = 10_000,
              failedRunRetention = retention,
          ),
          Clock.systemUTC(),
      ) {
        workspaceEvents += it
      }

  private fun runner(
      ws: Workspaces = workspaces(),
      max: Int = 4,
      unfinishedGrace: Duration = Duration.ofMillis(300),
  ): Runner = Runner(ws, max, unfinishedGrace).also { runners += it }

  @AfterTest fun closeRunners() = runners.forEach { it.close() }

  private fun shared(pipeline: String, file: String): Path =
      tmp.resolve("persistent").resolve(pipeline).resolve(file)

  private class Recorder : RunListener {
    val events = CopyOnWriteArrayList<RunEvent>()

    override fun onEvent(event: RunEvent) {
      events += event
    }

    val statuses
      get() = events.filterIsInstance<RunEvent.StatusChanged>().map { it.status }
  }

  private fun RunHandle.await(): RunResult = result.get(30, TimeUnit.SECONDS)

  @Test
  fun `runs a pipeline from a jar with its parameters and reports its status`() {
    val jar =
        TestJars.build(
            tmp,
            "hello.jar",
            mapOf(
                "Hello" to
                    TestJars.pipeline(
                        "Hello",
                        "hello",
                        """
                        context.getFiles().writeText(
                            FileScope.PIPELINE_SHARED, "out.txt", "hi " + context.getParameters().get("who"));
                        """
                            .trimIndent(),
                        extraAnnotation = ", parameters = {@Param(name = \"who\")}",
                    )
            ),
        )
    val listener = Recorder()

    val result =
        runner().start(RunRequest("run-1", jar, "Hello", mapOf("who" to "world")), listener).await()

    assertEquals(RunStatus.SUCCEEDED, result.status)
    assertEquals("hello", result.pipelineName)
    assertEquals("hi world", shared("hello", "out.txt").readText())
    assertEquals(
        listOf(
            RunStatus.INITIALIZING,
            RunStatus.RUNNING,
            RunStatus.SUCCEEDED,
        ),
        listener.statuses,
    )
  }

  private fun simpleJar(jarName: String, className: String, name: String, body: String): Path =
      TestJars.build(tmp, jarName, mapOf(className to TestJars.pipeline(className, name, body)))

  @Test
  fun `a failing pipeline reports its reason and does not affect other runs`() {
    val bad = simpleJar("bad.jar", "Bad", "bad", """throw new IllegalStateException("boom");""")
    val good =
        simpleJar(
            "good.jar",
            "Good",
            "good",
            """context.getFiles().writeText(FileScope.PIPELINE_SHARED, "ok.txt", "ok");""",
        )
    val runner = runner()
    val listener = Recorder()

    val failing = runner.start(RunRequest("run-bad", bad, "Bad"), listener)
    val fine = runner.start(RunRequest("run-good", good, "Good"), Recorder())

    val failed = failing.await()
    assertEquals(RunStatus.SUCCEEDED, fine.await().status)
    assertEquals("ok", shared("good", "ok.txt").readText())
    assertEquals(RunStatus.FAILED, failed.status)
    assertEquals("java.lang.IllegalStateException", failed.failure?.type)
    assertEquals("boom", failed.failure?.message)
    assertTrue(failed.failure!!.trace.contains("IllegalStateException: boom"))
    assertEquals(
        listOf(RunStatus.INITIALIZING, RunStatus.RUNNING, RunStatus.FAILED),
        listener.statuses,
    )
  }

  /** A host-only class: on the test classpath, so a leaking class loader parent would expose it. */
  class HostOnlyMarker

  private fun sharedLines(pipeline: String, file: String): List<String> =
      shared(pipeline, file).readText().lines().filter { it.isNotEmpty() }

  @Test
  fun `a run sees only its own loader's classes, not the host's and not other runs'`() {
    val body =
        """
        String tag = context.getParameters().get("tag");
        ((java.util.concurrent.CyclicBarrier) System.getProperties().get("rl.barrier"))
            .await(20, java.util.concurrent.TimeUnit.SECONDS);
        StringBuilder out = new StringBuilder();
        out.append("marker=").append(Marker.value()).append("\n");
        out.append("counter=").append(Counter.next()).append("\n");
        out.append("onlyInA=").append(visible("OnlyInA")).append("\n");
        out.append("host=").append(visible("dev.lawlan.runline.runner.RunnerTest${'$'}HostOnlyMarker")).append("\n");
        out.append("junit=").append(visible("org.junit.jupiter.api.Test")).append("\n");
        out.append("parent=").append(
            Probe.class.getClassLoader().getParent() == ClassLoader.getPlatformClassLoader()).append("\n");
        context.getFiles().writeText(FileScope.PIPELINE_SHARED, "seen-" + tag + ".txt", out.toString());
        """
            .trimIndent()
    val visible =
        """
        static boolean visible(String name) {
          try { Class.forName(name); return true; } catch (ClassNotFoundException e) { return false; }
        }
        """
            .trimIndent()
    val params = ", parameters = {@Param(name = \"tag\")}"
    fun jar(
        name: String,
        pipelineName: String,
        version: String,
        extra: Map<String, String> = emptyMap(),
    ) =
        TestJars.build(
            tmp,
            name,
            mapOf(
                "Probe" to TestJars.pipeline("Probe", pipelineName, body, params, visible),
                "Marker" to
                    "public class Marker { public static String value() { return \"$version\"; } }",
                "Counter" to
                    "public class Counter { private static int n; public static synchronized int next() { return ++n; } }",
            ) + extra,
        )
    val jarA = jar("a.jar", "iso-a", "v1", mapOf("OnlyInA" to "public class OnlyInA {}"))
    val jarB = jar("b.jar", "iso-b", "v2")
    System.getProperties()["rl.barrier"] = java.util.concurrent.CyclicBarrier(3)
    try {
      val runner = runner()
      val a1 = runner.start(RunRequest("a-1", jarA, "Probe", mapOf("tag" to "a1")), Recorder())
      val a2 = runner.start(RunRequest("a-2", jarA, "Probe", mapOf("tag" to "a2")), Recorder())
      val b = runner.start(RunRequest("b-1", jarB, "Probe", mapOf("tag" to "b")), Recorder())

      listOf(a1, a2, b).forEach { assertEquals(RunStatus.SUCCEEDED, it.await().status) }
    } finally {
      System.getProperties().remove("rl.barrier")
    }

    val expectedHidden = listOf("host=false", "junit=false", "parent=true")
    assertEquals(
        listOf("marker=v1", "counter=1", "onlyInA=true") + expectedHidden,
        sharedLines("iso-a", "seen-a1.txt"),
    )
    assertEquals(
        listOf("marker=v1", "counter=1", "onlyInA=true") + expectedHidden,
        sharedLines("iso-a", "seen-a2.txt"),
        "same jar in a second run gets its own copy of every class, statics included",
    )
    assertEquals(
        listOf("marker=v2", "counter=1", "onlyInA=false") + expectedHidden,
        sharedLines("iso-b", "seen-b.txt"),
        "same class names, different versions, side by side",
    )
  }

  @Test
  fun `a run executes on a platform thread named after the run with the run's context class loader`() {
    val jar =
        simpleJar(
            "thread.jar",
            "Probe",
            "thread-probe",
            """
            Thread t = Thread.currentThread();
            String out = "name=" + t.getName() + "\n"
                + "virtual=" + t.isVirtual() + "\n"
                + "ccl=" + (t.getContextClassLoader() == Probe.class.getClassLoader()) + "\n";
            context.getFiles().writeText(FileScope.PIPELINE_SHARED, "thread.txt", out);
            """
                .trimIndent(),
        )
    val runner = runner(max = 1)

    runner.start(RunRequest("run-t1", jar, "Probe"), Recorder()).await()
    runner.start(RunRequest("run-t2", jar, "Probe"), Recorder()).await()

    assertEquals(
        listOf("name=run-t2", "virtual=false", "ccl=true"),
        sharedLines("thread-probe", "thread.txt"),
        "the second run reuses the executor but gets its own name and loader",
    )
  }

  @Test
  fun `the class loader of a finished run can be garbage collected`() {
    val jar =
        TestJars.build(
            tmp,
            "gc.jar",
            mapOf(
                "Probe" to
                    TestJars.pipeline(
                        "Probe",
                        "gc-probe",
                        """
                        System.getProperties().put(
                            "rl.loader", new java.lang.ref.WeakReference<ClassLoader>(Probe.class.getClassLoader()));
                        """
                            .trimIndent(),
                    )
            ),
        )
    val result = runner().start(RunRequest("run-gc", jar, "Probe"), Recorder()).await()
    assertEquals(RunStatus.SUCCEEDED, result.status)

    @Suppress("UNCHECKED_CAST")
    val loader =
        System.getProperties().remove("rl.loader") as java.lang.ref.WeakReference<ClassLoader>
    val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
    while (loader.get() != null && System.nanoTime() < deadline) {
      System.gc()
      Thread.sleep(50)
    }
    assertEquals(null, loader.get(), "the run's class loader was still reachable after the run")
  }

  @Test
  fun `standard output and input are attributed to the run that used them`() {
    val jar =
        TestJars.build(
            tmp,
            "stdio.jar",
            mapOf(
                "Probe" to
                    TestJars.pipeline(
                        "Probe",
                        "stdio-probe",
                        """
                        String tag = context.getParameters().get("tag");
                        ((java.util.concurrent.CyclicBarrier) System.getProperties().get("rl.barrier"))
                            .await(20, java.util.concurrent.TimeUnit.SECONDS);
                        System.out.println("out-" + tag);
                        System.err.println("err-" + tag);
                        Thread child = new Thread(() -> System.out.println("child-" + tag));
                        child.start();
                        child.join();
                        System.out.print("partial-" + tag);
                        context.getFiles().writeText(
                            FileScope.PIPELINE_SHARED, "stdin-" + tag + ".txt", String.valueOf(System.in.read()));
                        """
                            .trimIndent(),
                        extraAnnotation = ", parameters = {@Param(name = \"tag\")}",
                    )
            ),
        )
    val first = Recorder()
    val second = Recorder()
    System.getProperties()["rl.barrier"] = java.util.concurrent.CyclicBarrier(2)
    try {
      val runner = runner()
      val a = runner.start(RunRequest("io-a", jar, "Probe", mapOf("tag" to "a")), first)
      val b = runner.start(RunRequest("io-b", jar, "Probe", mapOf("tag" to "b")), second)
      assertEquals(RunStatus.SUCCEEDED, a.await().status)
      assertEquals(RunStatus.SUCCEEDED, b.await().status)
    } finally {
      System.getProperties().remove("rl.barrier")
    }

    fun logs(r: Recorder, id: String, tag: String) =
        assertEquals(
            listOf(
                RunEvent.LogLine(id, LogStream.STDOUT, "out-$tag"),
                RunEvent.LogLine(id, LogStream.STDERR, "err-$tag"),
                RunEvent.LogLine(id, LogStream.STDOUT, "child-$tag"),
                RunEvent.LogLine(id, LogStream.STDOUT, "partial-$tag"),
            ),
            r.events.filterIsInstance<RunEvent.LogLine>(),
        )
    logs(first, "io-a", "a")
    logs(second, "io-b", "b")
    assertEquals(listOf("-1"), sharedLines("stdio-probe", "stdin-a.txt"), "a run's stdin is empty")
  }

  @Test
  fun `threads left behind by a run are reported`() {
    val leaky =
        simpleJar(
            "leaky.jar",
            "Leaky",
            "leaky",
            """
            Thread t = new Thread(() -> {
              try { Thread.sleep(600_000); } catch (InterruptedException e) { }
            }, "leftover-thread");
            t.setDaemon(true);
            t.start();
            """
                .trimIndent(),
        )
    val tidy = simpleJar("tidy.jar", "Tidy", "tidy", "")
    val runner = runner()

    try {
      val tidyResult = runner.start(RunRequest("run-tidy", tidy, "Tidy"), Recorder()).await()
      val leakyResult = runner.start(RunRequest("run-leaky", leaky, "Leaky"), Recorder()).await()

      assertEquals(emptyList(), tidyResult.residualThreads)
      assertEquals(listOf("leftover-thread"), leakyResult.residualThreads)
    } finally {
      Thread.getAllStackTraces()
          .keys
          .filter { it.name == "leftover-thread" }
          .forEach { it.interrupt() }
    }
  }

  private fun awaitFile(path: Path) {
    val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
    while (!Files.exists(path)) {
      check(System.nanoTime() < deadline) { "timed out waiting for $path" }
      Thread.sleep(10)
    }
  }

  @Test
  fun `cancelling interrupts a blocking call and the run ends as cancelled`() {
    val jar =
        simpleJar(
            "sleepy.jar",
            "Sleepy",
            "sleepy",
            """
            context.getFiles().writeText(FileScope.PIPELINE_SHARED, "started", "x");
            Thread.sleep(600_000);
            """
                .trimIndent(),
        )
    val listener = Recorder()
    val handle = runner().start(RunRequest("run-c1", jar, "Sleepy"), listener)
    awaitFile(shared("sleepy", "started"))

    handle.cancel()

    val result = handle.await()
    assertEquals(RunStatus.CANCELLED, result.status)
    assertEquals(RunStatus.CANCELLED, handle.status)
    assertEquals(
        listOf(RunStatus.INITIALIZING, RunStatus.RUNNING, RunStatus.CANCELLED),
        listener.statuses,
    )
  }

  @Test
  fun `cancellation is cooperative for code that does not block interruptibly`() {
    val jar =
        simpleJar(
            "stubborn.jar",
            "Stubborn",
            "stubborn",
            """
            context.getFiles().writeText(FileScope.PIPELINE_SHARED, "started", "x");
            while (!context.getFiles().exists(FileScope.PIPELINE_SHARED, "release")) {
              Thread.interrupted(); // swallows the interrupt
              Thread.onSpinWait();
            }
            """
                .trimIndent(),
        )
    val handle = runner().start(RunRequest("run-c2", jar, "Stubborn"), Recorder())
    awaitFile(shared("stubborn", "started"))

    handle.cancel()
    Thread.sleep(300)

    assertFalse(handle.result.isDone, "a run that ignores the interrupt keeps running")
    Files.writeString(shared("stubborn", "release"), "x")
    assertEquals(
        RunStatus.SUCCEEDED,
        handle.await().status,
        "a run that completes despite the request ended as it did, not as cancelled",
    )
  }

  @Test
  fun `a run cancelled before it starts never executes`() {
    val blocker =
        simpleJar(
            "blocker.jar",
            "Blocker",
            "blocker",
            """
            context.getFiles().writeText(FileScope.PIPELINE_SHARED, "started", "x");
            while (!context.getFiles().exists(FileScope.PIPELINE_SHARED, "release")) Thread.sleep(10);
            """
                .trimIndent(),
        )
    val never =
        simpleJar(
            "never.jar",
            "Never",
            "never",
            """context.getFiles().writeText(FileScope.PIPELINE_SHARED, "ran", "x");""",
        )
    val runner = runner(max = 1)
    val first = runner.start(RunRequest("run-q1", blocker, "Blocker"), Recorder())
    awaitFile(shared("blocker", "started"))
    val listener = Recorder()
    val queued = runner.start(RunRequest("run-q2", never, "Never"), listener)

    queued.cancel()
    Files.writeString(shared("blocker", "release"), "x")

    assertEquals(RunStatus.SUCCEEDED, first.await().status)
    assertEquals(RunStatus.CANCELLED, queued.await().status)
    assertFalse(Files.exists(shared("never", "ran")))
    assertEquals(listOf(RunStatus.CANCELLED), listener.statuses)
  }

  @Test
  fun `a run that exceeds its timeout is interrupted and ends as timed out`() {
    val jar = simpleJar("slow.jar", "Slow", "slow", "Thread.sleep(600_000);")
    val listener = Recorder()

    val result =
        runner()
            .start(RunRequest("run-to1", jar, "Slow", timeout = Duration.ofMillis(200)), listener)
            .await()

    assertEquals(RunStatus.TIMED_OUT, result.status)
    assertEquals(
        listOf(RunStatus.INITIALIZING, RunStatus.RUNNING, RunStatus.TIMED_OUT),
        listener.statuses,
    )
  }

  @Test
  fun `a run that ignores its timeout is marked timed out but unfinished until it ends`() {
    val jar =
        simpleJar(
            "deaf.jar",
            "Deaf",
            "deaf",
            """
            while (!context.getFiles().exists(FileScope.PIPELINE_SHARED, "release")) {
              Thread.interrupted();
              Thread.onSpinWait();
            }
            """
                .trimIndent(),
        )
    val listener = Recorder()
    val handle =
        runner()
            .start(RunRequest("run-to2", jar, "Deaf", timeout = Duration.ofMillis(100)), listener)

    val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
    while (handle.status != RunStatus.TIMED_OUT_UNFINISHED) {
      check(System.nanoTime() < deadline) { "never marked unfinished; status ${handle.status}" }
      Thread.sleep(10)
    }
    assertFalse(handle.result.isDone)

    Files.writeString(shared("deaf", "release"), "x")
    handle.await()
    assertEquals(
        listOf(
            RunStatus.INITIALIZING,
            RunStatus.RUNNING,
            RunStatus.TIMED_OUT_UNFINISHED,
            RunStatus.SUCCEEDED,
        ),
        listener.statuses,
    )
  }

  private fun scratch(pipeline: String, runId: String, file: String = ""): Path =
      tmp.resolve("scratch").resolve(pipeline).resolve(runId).resolve(file)

  private fun removedPrivate(runId: String) = workspaceEvents.count {
    it is WorkspaceEvent.Removed && it.scope == FileScope.RUN_PRIVATE && it.runId == runId
  }

  private val usesBothScopes =
      """
      context.getFiles().writeText(FileScope.RUN_PRIVATE, "mine.txt", "private");
      context.getFiles().writeText(FileScope.PIPELINE_SHARED, "kept.txt", "shared");
      """
          .trimIndent()

  @Test
  fun `a successful run's private directory is removed at once and the shared one stays`() {
    val jar = simpleJar("ok.jar", "Ok", "ok", usesBothScopes)

    val result = runner().start(RunRequest("run-w1", jar, "Ok"), Recorder()).await()

    assertEquals(RunStatus.SUCCEEDED, result.status)
    assertFalse(Files.exists(scratch("ok", "run-w1")))
    assertEquals("shared", shared("ok", "kept.txt").readText())
    assertEquals(1, removedPrivate("run-w1"))
  }

  @Test
  fun `failed, cancelled and timed out runs keep their private directory until retention expires`() {
    val failing =
        simpleJar("f.jar", "F", "wf", usesBothScopes + """throw new RuntimeException("x");""")
    val sleepy =
        simpleJar(
            "s.jar",
            "S",
            "ws",
            usesBothScopes +
                """context.getFiles().writeText(FileScope.PIPELINE_SHARED, "started", "x"); Thread.sleep(600_000);""",
        )
    val ws = workspaces(retention = Duration.ZERO)
    val runner = runner(ws)

    runner.start(RunRequest("run-failed", failing, "F"), Recorder()).await()
    val cancelled = runner.start(RunRequest("run-cancelled", sleepy, "S"), Recorder())
    awaitFile(shared("ws", "started"))
    cancelled.cancel()
    cancelled.await()
    runner
        .start(RunRequest("run-timeout", sleepy, "S", timeout = Duration.ofMillis(200)), Recorder())
        .await()

    assertEquals("private", scratch("wf", "run-failed", "mine.txt").readText())
    assertEquals("private", scratch("ws", "run-cancelled", "mine.txt").readText())
    assertEquals("private", scratch("ws", "run-timeout", "mine.txt").readText())
    assertEquals(
        0,
        removedPrivate("run-failed") +
            removedPrivate("run-cancelled") +
            removedPrivate("run-timeout"),
    )

    ws.sweep()

    assertFalse(Files.exists(scratch("wf", "run-failed")))
    assertFalse(Files.exists(scratch("ws", "run-cancelled")))
    assertFalse(Files.exists(scratch("ws", "run-timeout")))
  }

  @Test
  fun `a run cannot reach another run's private directory`() {
    val writer = simpleJar("w.jar", "W", "pw", usesBothScopes + """Thread.sleep(600_000);""")
    val reader =
        simpleJar(
            "r.jar",
            "R",
            "pr",
            """
            context.getFiles().writeText(
                FileScope.PIPELINE_SHARED, "saw.txt",
                String.valueOf(context.getFiles().exists(FileScope.RUN_PRIVATE, "mine.txt")));
            """
                .trimIndent(),
        )
    val runner = runner()
    val first = runner.start(RunRequest("run-owner", writer, "W"), Recorder())
    awaitFile(scratch("pw", "run-owner", "mine.txt"))

    runner.start(RunRequest("run-other", reader, "R"), Recorder()).await()
    first.cancel()
    first.await()

    assertEquals("false", shared("pr", "saw.txt").readText())
  }

  @Test
  fun `writes beyond the workspace limit fail the run with a clear error`() {
    val jar =
        simpleJar(
            "big.jar",
            "Big",
            "big",
            """context.getFiles().writeText(FileScope.PIPELINE_SHARED, "big.txt", "x".repeat(20_000));""",
        )

    val result = runner().start(RunRequest("run-big", jar, "Big"), Recorder()).await()

    assertEquals(RunStatus.FAILED, result.status)
    assertEquals("dev.lawlan.runline.core.FileQuotaExceeded", result.failure?.type)
  }

  @Test
  fun `a run id already in use within the pipeline is rejected without disturbing the first run`() {
    val jar =
        simpleJar(
            "dup.jar",
            "Dup",
            "dup",
            """
            context.getFiles().writeText(FileScope.PIPELINE_SHARED, "started", "x");
            while (!context.getFiles().exists(FileScope.PIPELINE_SHARED, "release")) Thread.sleep(10);
            """
                .trimIndent(),
        )
    val runner = runner()
    val first = runner.start(RunRequest("run-same", jar, "Dup"), Recorder())
    awaitFile(shared("dup", "started"))

    val second = runner.start(RunRequest("run-same", jar, "Dup"), Recorder()).await()
    Files.writeString(shared("dup", "release"), "x")

    assertEquals(RunStatus.FAILED, second.status)
    assertEquals("java.lang.IllegalArgumentException", second.failure?.type)
    assertEquals(RunStatus.SUCCEEDED, first.await().status)
    assertEquals(1, removedPrivate("run-same"), "the first run ended exactly once")
  }

  @Test
  fun `no more runs execute at once than the configured limit`() {
    val jar =
        TestJars.build(
            tmp,
            "cap.jar",
            mapOf(
                "Cap" to
                    TestJars.pipeline(
                        "Cap",
                        "cap",
                        """
                        String tag = context.getParameters().get("tag");
                        context.getFiles().writeText(FileScope.PIPELINE_SHARED, "started-" + tag, "x");
                        while (!context.getFiles().exists(FileScope.PIPELINE_SHARED, "release")) Thread.sleep(10);
                        """
                            .trimIndent(),
                        extraAnnotation = ", parameters = {@Param(name = \"tag\")}",
                    )
            ),
        )
    val runner = runner(max = 2)
    val handles =
        (1..3).map {
          runner.start(RunRequest("cap-$it", jar, "Cap", mapOf("tag" to "$it")), Recorder())
        }
    awaitFile(shared("cap", "started-1"))
    awaitFile(shared("cap", "started-2"))
    Thread.sleep(300)

    assertFalse(Files.exists(shared("cap", "started-3")), "the third run waits for a free thread")

    Files.writeString(shared("cap", "release"), "x")
    handles.forEach { assertEquals(RunStatus.SUCCEEDED, it.await().status) }
    assertTrue(Files.exists(shared("cap", "started-3")))
  }

  @Test
  fun `runs that cannot be set up report a failure instead of hanging`() {
    val ok = simpleJar("setup.jar", "Setup", "setup", "")
    val needsParam =
        TestJars.build(
            tmp,
            "param.jar",
            mapOf(
                "NeedsParam" to
                    TestJars.pipeline(
                        "NeedsParam",
                        "needs-param",
                        "",
                        ", parameters = {@Param(name = \"x\")}",
                    )
            ),
        )
    val runner = runner()

    val missingJar =
        runner.start(RunRequest("e-1", tmp.resolve("absent.jar"), "Setup"), Recorder()).await()
    val missingClass = runner.start(RunRequest("e-2", ok, "NoSuchClass"), Recorder()).await()
    val badRunId = runner.start(RunRequest("../escape", ok, "Setup"), Recorder()).await()
    val missingParameter =
        runner.start(RunRequest("e-4", needsParam, "NeedsParam"), Recorder()).await()

    listOf(missingJar, missingClass, badRunId, missingParameter).forEach {
      assertEquals(RunStatus.FAILED, it.status)
    }
    assertEquals("java.lang.ClassNotFoundException", missingClass.failure?.type)
    assertEquals("java.lang.IllegalArgumentException", badRunId.failure?.type)
    assertEquals("java.lang.IllegalArgumentException", missingParameter.failure?.type)
    assertEquals(
        0,
        workspaceEvents.count {
          it is WorkspaceEvent.Created && it.runId in setOf("e-1", "e-2", "../escape")
        },
        "runs that failed before initialization finished leave no run directory behind",
    )
  }

  @Test
  fun `a closed runner takes no new runs but lets running ones finish`() {
    val jar =
        simpleJar(
            "closing.jar",
            "Closing",
            "closing",
            """
            context.getFiles().writeText(FileScope.PIPELINE_SHARED, "started", "x");
            while (!context.getFiles().exists(FileScope.PIPELINE_SHARED, "release")) Thread.sleep(10);
            """
                .trimIndent(),
        )
    val runner = runner()
    val running = runner.start(RunRequest("run-closing", jar, "Closing"), Recorder())
    awaitFile(shared("closing", "started"))

    runner.close()

    assertFailsWith<java.util.concurrent.RejectedExecutionException> {
      runner.start(RunRequest("run-late", jar, "Closing"), Recorder())
    }
    Files.writeString(shared("closing", "release"), "x")
    assertEquals(RunStatus.SUCCEEDED, running.await().status)
  }
}
