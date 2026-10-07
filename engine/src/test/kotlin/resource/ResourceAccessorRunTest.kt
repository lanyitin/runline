package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.analyzer.Verdict
import dev.lawlan.runline.engine.auth.ApiIdentity
import dev.lawlan.runline.engine.auth.Role
import dev.lawlan.runline.engine.run.CreateRunResult
import dev.lawlan.runline.engine.run.RunState
import dev.lawlan.runline.engine.support.RunHarness
import dev.lawlan.runline.engine.support.RunHarness.Companion.holdUntilReleased
import dev.lawlan.runline.engine.support.RunHarness.Companion.using
import dev.lawlan.runline.engine.support.RunHarness.Companion.usingTyped
import java.nio.file.Files
import java.time.Duration
import kotlin.test.*

/**
 * Typed resources through the whole run machinery with everything real: PostgreSQL, the real
 * coordinator and scheduler, the Runner with a class loader per run, pipelines compiled from source
 * and uploaded, and the real file system under the resource root (ADR-019, WI-43).
 */
class ResourceAccessorRunTest {
  private val harnesses = mutableListOf<RunHarness>()

  private fun harness(maxConcurrent: Int = 3) =
      RunHarness(maxConcurrent = maxConcurrent, resourceWaitTimeout = Duration.ofHours(1)).also {
        harnesses += it
      }

  @AfterTest fun closeAll() = harnesses.forEach { it.close() }

  @Test
  fun `a run that declared the file type writes and reads the file through its accessor`() {
    val h = harness()
    h.defineFile("log", "logs/out.txt")
    val hash =
        h.upload(
            "writer",
            """
            FileAccessor log = context.getAccessors().file("log");
            log.writeText("hello");
            context.getFiles().writeText(FileScope.PIPELINE_SHARED, "back", log.readText());
            """
                .trimIndent(),
            declaration = usingTyped("log" to "file"),
        )

    val run = h.awaitEnd(h.start(hash, "writer"))

    assertEquals(RunState.SUCCEEDED, run.state, run.failure?.message)
    assertEquals("hello", Files.readString(h.resourceRoot.resolve("logs/out.txt")))
    assertEquals("hello", Files.readString(h.shared("writer", "back")))
  }

  private val admin = ApiIdentity("root", Role.ADMIN)

  private fun path(value: String) =
      kotlinx.serialization.json.JsonObject(
          mapOf("path" to kotlinx.serialization.json.JsonPrimitive(value))
      )

  private fun RunHarness.release(pipeline: String) =
      Files.writeString(shared(pipeline, "release"), "x")

  // ---- who may use an accessor ----

  @Test
  fun `a run that declared the name only gets the capacity and no accessor, and a typed run waits for it`() {
    val h = harness()
    h.defineFile("log", "out.txt")
    val nameOnly =
        h.upload(
            "name-only",
            """
            ${holdUntilReleased("started")}
            String result = "none";
            try { context.getAccessors().file("log"); }
            catch (ResourceAccessException e) { result = e.getFailure().name(); }
            context.getFiles().writeText(FileScope.PIPELINE_SHARED, "result", result);
            """
                .trimIndent(),
            declaration = using("log"),
        )
    val typed =
        h.upload(
            "typed",
            """context.getAccessors().file("log").writeText("typed");""",
            declaration = usingTyped("log" to "file"),
        )
    val first = h.start(nameOnly, "name-only")
    h.awaitFile(h.shared("name-only", "started"))

    val second = h.start(typed, "typed")

    h.await(second, RunState.WAITING_FOR_RESOURCES)
    h.release("name-only")
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(first).state)
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(second).state)
    assertEquals("NO_TYPE_DECLARED", Files.readString(h.shared("name-only", "result")))
    assertEquals("typed", Files.readString(h.resourceRoot.resolve("out.txt")))
  }

  @Test
  fun `a type declared in the other direction is refused when the run is made, with a real file resource`() {
    val h = harness()
    h.defineFile("log", "out.txt")
    h.defineResource("gate", 1)
    val wantsCounter = h.upload("a", "", declaration = usingTyped("log" to "counter"))
    val wantsFile = h.upload("b", "", declaration = usingTyped("gate" to "file"))

    val a = h.create(wantsCounter, "a")
    val b = h.create(wantsFile, "b")

    assertEquals(
        listOf(ResourceProblem("log", ResourceProblemKind.TYPE_MISMATCH)),
        assertIs<CreateRunResult.ResourcesUnavailable>(a).problems,
    )
    assertEquals(
        listOf(ResourceProblem("gate", ResourceProblemKind.TYPE_MISMATCH)),
        assertIs<CreateRunResult.ResourcesUnavailable>(b).problems,
    )
  }

  // ---- getting ready ----

  @Test
  fun `a resource whose root is gone fails the run as unavailable before its body, and frees the capacity`() {
    val h = harness()
    h.defineFile("log", "out.txt")
    val hash =
        h.upload(
            "needs-file",
            """context.getFiles().writeText(FileScope.PIPELINE_SHARED, "entered", "x");""",
            declaration = usingTyped("log" to "file"),
        )
    val moved = h.resourceRoot.resolveSibling("resource-root-moved")
    Files.move(h.resourceRoot, moved)

    val failed = h.awaitEnd(h.start(hash, "needs-file"))

    assertEquals(RunState.FAILED, failed.state)
    assertEquals(ResourceFailures.UNAVAILABLE, failed.failure!!.type)
    assertTrue(failed.failure!!.message!!.contains("log"), failed.failure!!.message)
    assertFalse(h.exists("needs-file", "entered"))
    assertEquals(emptyList(), h.coordinator!!.activity("log").holders)
    Files.move(moved, h.resourceRoot)
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(h.start(hash, "needs-file")).state)
  }

  @Test
  fun `a run keeps the settings it was granted while a later run gets the changed ones`() {
    val h = harness(maxConcurrent = 2)
    h.defineFile("log", "old.txt", capacity = 2)
    val body =
        h.upload(
            "writer",
            """
        ${holdUntilReleased("started")}
        context.getAccessors().file("log").writeText(context.getParameters().get("who"));
        """
                .trimIndent(),
            declaration = usingTyped("log" to "file") + ", parameters = {@Param(name = \"who\")}",
        )
    val first = h.start(body, "writer", mapOf("who" to "first"))
    h.awaitFile(h.shared("writer", "started"))

    h.resourceAdmin.update("log", null, null, admin, settings = path("new.txt"))
    val second = h.start(body, "writer", mapOf("who" to "second"))
    h.release("writer")

    assertEquals(RunState.SUCCEEDED, h.awaitEnd(first).state)
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(second).state)
    assertEquals("first", Files.readString(h.resourceRoot.resolve("old.txt")))
    assertEquals("second", Files.readString(h.resourceRoot.resolve("new.txt")))
  }

  // ---- giving back ----

  private val strayAfterEnd =
      """
      final FileAccessor log = context.getAccessors().file("log");
      final PipelineContext ctx = context;
      log.writeText("early");
      // What is not loaded yet cannot be loaded once the run's loader is closed.
      new ResourceAccessException("preload", ResourceFailure.FAILED, null);
      ctx.getFiles().exists(FileScope.PIPELINE_SHARED, "go");
      ctx.getFiles().writeText(FileScope.PIPELINE_SHARED, "warm", "x");
      new Thread(() -> {
        while (!ctx.getFiles().exists(FileScope.PIPELINE_SHARED, "go")) Thread.onSpinWait();
        String result = "ok";
        try { log.writeText("late"); }
        catch (ResourceAccessException e) { result = e.getFailure().name(); }
        catch (Throwable t) { result = t.toString(); }
        ctx.getFiles().writeText(FileScope.PIPELINE_SHARED, "late-outcome", result);
      }).start();
      """
          .trimIndent()

  @Test
  fun `when a run ends its accessor stops working and nothing it does afterwards reaches the file`() {
    val h = harness()
    h.defineFile("log", "out.txt")
    val hash = h.upload("straggler", strayAfterEnd, declaration = usingTyped("log" to "file"))

    val ended = h.awaitEnd(h.start(hash, "straggler"))
    Files.writeString(h.shared("straggler", "go"), "x")
    h.awaitFile(h.shared("straggler", "late-outcome"))

    assertEquals(RunState.SUCCEEDED, ended.state)
    assertEquals("ENDED", Files.readString(h.shared("straggler", "late-outcome")))
    assertEquals("early", Files.readString(h.resourceRoot.resolve("out.txt")))
  }

  @Test
  fun `a forced release invalidates the accessor, the next waiter gets the file and the old write never lands`() {
    val h = harness(maxConcurrent = 2)
    h.defineFile("log", "out.txt")
    val holder =
        h.upload(
            "holder",
            """
            FileAccessor log = context.getAccessors().file("log");
            log.writeText("holder-1");
            context.getFiles().writeText(FileScope.PIPELINE_SHARED, "wrote", "x");
            while (!context.getFiles().exists(FileScope.PIPELINE_SHARED, "go")) Thread.onSpinWait();
            String result = "ok";
            try { log.writeText("holder-2"); }
            catch (ResourceAccessException e) { result = e.getFailure().name(); }
            context.getFiles().writeText(FileScope.PIPELINE_SHARED, "outcome", result);
            """
                .trimIndent(),
            declaration = usingTyped("log" to "file"),
        )
    val next =
        h.upload(
            "next",
            """context.getAccessors().file("log").writeText("next");""",
            declaration = usingTyped("log" to "file"),
        )
    val first = h.start(holder, "holder")
    h.awaitFile(h.shared("holder", "wrote"))
    val second = h.start(next, "next")
    h.await(second, RunState.WAITING_FOR_RESOURCES)

    h.coordinator!!.forceRelease("log", first, admin)
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(second).state)
    Files.writeString(h.shared("holder", "go"), "x")

    assertEquals(RunState.SUCCEEDED, h.awaitEnd(first).state)
    assertEquals("FORCE_RELEASED", Files.readString(h.shared("holder", "outcome")))
    assertEquals("next", Files.readString(h.resourceRoot.resolve("out.txt")))
  }

  @Test
  fun `disabling the resource or lowering its capacity leaves the holder's accessor working`() {
    val h = harness()
    h.defineFile("log", "out.txt", capacity = 2)
    val hash =
        h.upload(
            "holder",
            """
            FileAccessor log = context.getAccessors().file("log");
            context.getFiles().writeText(FileScope.PIPELINE_SHARED, "started", "x");
            while (!context.getFiles().exists(FileScope.PIPELINE_SHARED, "release")) Thread.onSpinWait();
            log.writeText("still mine");
            """
                .trimIndent(),
            declaration = usingTyped("log" to "file"),
        )
    val run = h.start(hash, "holder")
    h.awaitFile(h.shared("holder", "started"))

    h.resourceAdmin.update("log", 1, false, admin)
    h.release("holder")

    assertEquals(RunState.SUCCEEDED, h.awaitEnd(run).state)
    assertEquals("still mine", Files.readString(h.resourceRoot.resolve("out.txt")))
  }

  private fun RunHarness.exists(pipeline: String, file: String) =
      Files.exists(shared(pipeline, file))

  @Test
  fun `a pipeline that declares typed resources and uses their accessors is still safe`() {
    val h = harness()
    val hash =
        h.upload(
            "safe-user",
            """
            FileAccessor log = context.getAccessors().file("log");
            log.writeText("x");
            log.readText();
            """
                .trimIndent(),
            declaration = usingTyped("log" to "file"),
        )

    assertEquals(Verdict.SAFE, h.definitions.find(hash, "safe-user")!!.verdict)
  }
}
