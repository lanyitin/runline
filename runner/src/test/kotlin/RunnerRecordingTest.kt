package dev.lawlan.runline.runner

import dev.lawlan.runline.core.FileScope
import dev.lawlan.runline.core.IoAccess
import dev.lawlan.runline.core.IoCategory
import dev.lawlan.runline.runner.support.TestJars
import java.net.ServerSocket
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class RunnerRecordingTest {
  @TempDir lateinit var tmp: Path

  private val runners = mutableListOf<Runner>()

  private fun runner(): Runner =
      Runner(
              Workspaces(
                  WorkspaceConfig(
                      sharedRoot = tmp.resolve("persistent"),
                      runRoot = tmp.resolve("scratch"),
                      maxBytesPerScope = 10_000,
                      failedRunRetention = Duration.ofHours(1),
                  ),
                  Clock.systemUTC(),
              ) {},
              maxConcurrentRuns = 2,
              unfinishedGrace = Duration.ofMillis(300),
          )
          .also { runners += it }

  @AfterTest fun closeRunners() = runners.forEach { it.close() }

  /** A pipeline that declares no file scope and an empty network and process range. */
  private fun declaresNothing(className: String, body: String): Path =
      TestJars.build(
          tmp,
          "$className.jar",
          mapOf(
              className to
                  """
                  import dev.lawlan.runline.core.*;
                  import java.util.List;

                  @PipelineDefinition(
                      name = "${className.lowercase()}",
                      network = @AccessLimit(allow = {}),
                      processes = @AccessLimit(allow = {}))
                  public class $className implements Pipeline {
                    @Override
                    public void run(PipelineContext context) {
                      try { $body } catch (RuntimeException e) { throw e; }
                      catch (Exception e) { throw new RuntimeException(e); }
                    }
                  }
                  """
                      .trimIndent()
          ),
      )

  private fun run(jar: Path, cls: String, recording: RecordingOptions?): RunResult =
      runner()
          .start(RunRequest("run-${cls.lowercase()}", jar, cls, recording = recording)) {}
          .result
          .get(60, TimeUnit.SECONDS)

  private val everything =
      """
      context.getFiles().writeText(FileScope.PIPELINE_SHARED, "a.txt", "content");
      context.getFiles().readText(FileScope.PIPELINE_SHARED, "a.txt");
      context.getProcesses().run(List.of("echo", "arg"));
      """
          .trimIndent()

  @Test
  fun `a normal run has no recording and enforces what the pipeline declares`() {
    val result = run(declaresNothing("Plain", everything), "Plain", recording = null)

    assertEquals(RunStatus.FAILED, result.status)
    assertTrue("PipelineAccessDenied" in result.failure!!.type, result.failure.toString())
    assertNull(result.recording)
  }

  @Test
  fun `a recording run allows what is not declared and returns what it did`() {
    val result = run(declaresNothing("Wild", everything), "Wild", RecordingOptions())

    assertEquals(RunStatus.SUCCEEDED, result.status, result.failure?.trace)
    val recording = assertNotNull(result.recording)
    assertEquals(
        listOf(
            Triple(IoCategory.FILE, "a.txt", IoAccess.WRITE),
            Triple(IoCategory.FILE, "a.txt", IoAccess.READ),
            Triple(IoCategory.PROCESS, "echo", IoAccess.WRITE),
        ),
        recording.events.map { Triple(it.category, it.target, it.access) },
    )
    assertEquals(listOf(1L, 2L, 3L), recording.events.map { it.sequence })
    assertEquals(FileScope.PIPELINE_SHARED, recording.events.first().scope)
    assertEquals(3L, recording.total)
  }

  @Test
  fun `a connection of a recording run records host and port`() {
    ServerSocket(0).use { server ->
      val jar =
          declaresNothing(
              "Dial",
              """context.getNetwork().connect("localhost", ${server.localPort}).close();""",
          )

      val result = run(jar, "Dial", RecordingOptions())

      assertEquals(RunStatus.SUCCEEDED, result.status, result.failure?.trace)
      val event = result.recording!!.events.single()
      assertEquals("localhost", event.target)
      assertEquals(server.localPort, event.port)
    }
  }

  @Test
  fun `a failing recording run still returns what it recorded up to the failure`() {
    val jar =
        declaresNothing(
            "Fails",
            """
            context.getProcesses().run(List.of("echo", "x"));
            throw new IllegalStateException("boom");
            """
                .trimIndent(),
        )

    val result = run(jar, "Fails", RecordingOptions())

    assertEquals(RunStatus.FAILED, result.status)
    assertEquals(listOf("echo"), result.recording!!.events.map { it.target })
  }

  @Test
  fun `a refused boundary violation is recorded as rejected`() {
    val jar =
        declaresNothing(
            "Escapes",
            """
            try { context.getFiles().readText(FileScope.RUN_PRIVATE, "../x"); }
            catch (PipelineAccessDenied expected) { }
            """
                .trimIndent(),
        )

    val result = run(jar, "Escapes", RecordingOptions())

    assertEquals(RunStatus.SUCCEEDED, result.status, result.failure?.trace)
    assertTrue(result.recording!!.events.single().rejected)
  }

  @Test
  fun `the event limit applies and the summary still counts everything`() {
    val jar =
        declaresNothing(
            "Chatty",
            """
            for (int i = 0; i < 50; i++) context.getProcesses().run(List.of("echo", "x"));
            """
                .trimIndent(),
        )

    val result = run(jar, "Chatty", RecordingOptions(maxEvents = 4))

    val recording = result.recording!!
    assertEquals(4, recording.events.size)
    assertEquals(50L, recording.total)
    assertEquals(50L, recording.summary.single().count)
    assertFalse(recording.summary.single().rejected)
  }
}
