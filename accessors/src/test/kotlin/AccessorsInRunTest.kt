package dev.lawlan.runline.accessors

import dev.lawlan.runline.runner.ResourceHost
import dev.lawlan.runline.runner.RunListener
import dev.lawlan.runline.runner.RunRequest
import dev.lawlan.runline.runner.RunResult
import dev.lawlan.runline.runner.RunStatus
import dev.lawlan.runline.runner.Runner
import dev.lawlan.runline.runner.WorkspaceConfig
import dev.lawlan.runline.runner.Workspaces
import dev.lawlan.runline.runner.support.TestJars
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

/**
 * Typed resources through the real Runner: a real jar in a real class loader of its own, a real
 * file on the real file system (ADR-019). The accessor is the run's class loader's own object; the
 * host's classes behind it are not visible to the run.
 */
class AccessorsInRunTest {
  @TempDir lateinit var tmp: Path

  private val runners = mutableListOf<Runner>()

  private val resourceRoot: Path by lazy { tmp.resolve("resources").createDirectories() }

  private fun runner(): Runner =
      Runner(
              Workspaces(
                  WorkspaceConfig(
                      tmp.resolve("persistent"),
                      tmp.resolve("scratch"),
                      10_000,
                      Duration.ofHours(1),
                  ),
                  Clock.systemUTC(),
              ) {},
              4,
              Duration.ofMillis(300),
          )
          .also { runners += it }

  @AfterTest fun closeRunners() = runners.forEach { it.close() }

  private fun shared(pipeline: String, file: String): Path =
      tmp.resolve("persistent").resolve(pipeline).resolve(file)

  private fun fileHost(vararg names: Pair<String, String>) =
      BoundResources(
          names.associate { (name, path) -> name to FileBinding(FileEntity(resourceRoot, path)) }
      )

  private fun jar(name: String, body: String, types: String = "\"notes\", type = \"file\""): Path =
      TestJars.build(
          tmp,
          "$name.jar",
          mapOf(
              "Probe" to
                  TestJars.pipeline(
                      "Probe",
                      name,
                      body,
                      extraAnnotation =
                          ", resources = {\"notes\"}, typedResources = {@TypedResource(name = $types)}",
                      extraMembers =
                          """
                          static boolean visible(String name) {
                            try { Class.forName(name); return true; } catch (ClassNotFoundException e) { return false; }
                          }
                          """
                              .trimIndent(),
                  )
          ),
      )

  private fun run(jar: Path, host: ResourceHost?, id: String = "run-1"): RunResult =
      runner()
          .start(RunRequest(id, jar, "Probe", resources = host), RunListener {})
          .result
          .get(30, TimeUnit.SECONDS)

  @Test
  fun `a pipeline writes and reads its file through an accessor that lives in its own class loader`() {
    val jar =
        jar(
            "writer",
            """
            FileAccessor notes = context.getAccessors().file("notes");
            notes.writeText("hello");
            String back = notes.readText();
            context.getFiles().writeText(FileScope.PIPELINE_SHARED, "seen.txt",
                back
                + "\nsameLoader=" + (context.getAccessors().getClass().getClassLoader() == getClass().getClassLoader())
                + "\nhostEntity=" + visible("dev.lawlan.runline.accessors.FileEntity")
                + "\nhostBinding=" + visible("dev.lawlan.runline.accessors.BoundResources"));
            """
                .trimIndent(),
        )

    val result = run(jar, fileHost("notes" to "notes.txt"))

    assertEquals(RunStatus.SUCCEEDED, result.status, result.failure?.trace)
    assertEquals("hello", resourceRoot.resolve("notes.txt").readText())
    assertEquals(
        listOf("hello", "sameLoader=true", "hostEntity=false", "hostBinding=false"),
        shared("writer", "seen.txt").readText().lines(),
    )
  }

  private fun awaitFile(path: Path) {
    val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
    while (!path.exists()) {
      check(System.nanoTime() < deadline) { "$path did not appear" }
      Thread.sleep(10)
    }
  }

  /** The Java code that runs [body] and writes the failure it raised, or "ok", to [outcome]. */
  private fun recordingOutcome(outcome: String, body: String) =
      """
      String result = "ok";
      try { $body } catch (ResourceAccessException e) { result = e.getFailure().name(); } catch (Throwable t) { result = t.toString(); }
      context.getFiles().writeText(FileScope.PIPELINE_SHARED, "$outcome", result);
      """
          .trimIndent()

  @Test
  fun `a run that has ended cannot use its accessor any more and changes nothing`() {
    val jar =
        jar(
            "straggler",
            """
            final FileAccessor notes = context.getAccessors().file("notes");
            final PipelineContext ctx = context;
            notes.writeText("early");
            // Classes that are not loaded yet cannot be loaded once the run's loader is closed.
            new ResourceAccessException("preload", ResourceFailure.FAILED, null);
            ctx.getFiles().exists(FileScope.PIPELINE_SHARED, "go");
            ctx.getFiles().writeText(FileScope.PIPELINE_SHARED, "warm", "x");
            new Thread(() -> {
              while (!ctx.getFiles().exists(FileScope.PIPELINE_SHARED, "go")) Thread.onSpinWait();
              ${recordingOutcome("late-outcome", "notes.writeText(\"late\");")}
            }).start();
            """
                .trimIndent(),
        )
    val host = fileHost("notes" to "notes.txt")

    val result = run(jar, host)
    host.invalidateAll(Invalidation.RUN_ENDED)
    Files.writeString(shared("straggler", "go"), "x")
    awaitFile(shared("straggler", "late-outcome"))

    assertEquals(RunStatus.SUCCEEDED, result.status, result.failure?.trace)
    assertEquals("ENDED", shared("straggler", "late-outcome").readText())
    assertEquals("early", resourceRoot.resolve("notes.txt").readText())
  }

  @Test
  fun `an administrator's forced release fails the holder's next operation and keeps its write out of the file`() {
    val jar =
        jar(
            "forced",
            """
            FileAccessor notes = context.getAccessors().file("notes");
            notes.writeText("before");
            context.getFiles().writeText(FileScope.PIPELINE_SHARED, "wrote", "x");
            while (!context.getFiles().exists(FileScope.PIPELINE_SHARED, "go")) Thread.onSpinWait();
            ${recordingOutcome("after-outcome", "notes.writeText(\"after\");")}
            """
                .trimIndent(),
        )
    val host = fileHost("notes" to "notes.txt")
    val running =
        runner().start(RunRequest("run-1", jar, "Probe", resources = host), RunListener {})
    awaitFile(shared("forced", "wrote"))

    host.invalidate("notes", Invalidation.FORCE_RELEASED)
    Files.writeString(shared("forced", "go"), "x")

    assertEquals(RunStatus.SUCCEEDED, running.result.get(30, TimeUnit.SECONDS).status)
    assertEquals("FORCE_RELEASED", shared("forced", "after-outcome").readText())
    assertEquals("before", resourceRoot.resolve("notes.txt").readText())
  }
}
