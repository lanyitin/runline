package dev.lawlan.runline.runner

import dev.lawlan.runline.runner.support.TestJars
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

/**
 * Every run a Runner accepts ends in a terminal result, whatever goes wrong on its start path: the
 * result never stays pending, and the thread (concurrency slot) and bookkeeping are released.
 */
class RunnerTerminalStateTest {
  @TempDir lateinit var tmp: Path

  private val runners = mutableListOf<Runner>()

  @AfterTest fun closeRunners() = runners.forEach { it.close() }

  private class Recorder : RunListener {
    val events = CopyOnWriteArrayList<RunEvent>()

    override fun onEvent(event: RunEvent) {
      events += event
    }

    val statuses
      get() = events.filterIsInstance<RunEvent.StatusChanged>().map { it.status }
  }

  /** An observer that fails when a private directory is removed, as a broken telemetry would. */
  private fun runner(observer: WorkspaceObserver): Runner =
      Runner(
              Workspaces(
                  WorkspaceConfig(
                      sharedRoot = tmp.resolve("persistent"),
                      runRoot = tmp.resolve("scratch"),
                      maxBytesPerScope = 10_000,
                      failedRunRetention = Duration.ofHours(1),
                  ),
                  Clock.systemUTC(),
                  observer,
              ),
              maxConcurrentRuns = 1,
          )
          .also { runners += it }

  private fun jar(name: String): Path =
      TestJars.build(tmp, "$name.jar", mapOf("P" to TestJars.pipeline("P", name, "")))

  private fun RunHandle.await(): RunResult = result.get(5, TimeUnit.SECONDS)

  @Test
  fun `an unexpected failure while ending a run still gives the run a failed result`() {
    val failing = WorkspaceObserver {
      if (it is WorkspaceEvent.Removed && it.runId == "run-1") error("telemetry is down")
    }
    val listener = Recorder()

    val result = runner(failing).start(RunRequest("run-1", jar("ending"), "P"), listener).await()

    assertEquals(RunStatus.FAILED, result.status)
    assertEquals("java.lang.IllegalStateException", result.failure?.type)
    assertEquals("telemetry is down", result.failure?.message)
    assertEquals(RunStatus.FAILED, listener.statuses.last())
  }

  @Test
  fun `the slot of a run that failed unexpectedly is free for the next run`() {
    val failing = WorkspaceObserver {
      if (it is WorkspaceEvent.Removed && it.runId == "run-1") error("telemetry is down")
    }
    val runner = runner(failing)
    val jar = jar("slot")

    runner.start(RunRequest("run-1", jar, "P"), Recorder()).await()
    val next = runner.start(RunRequest("run-2", jar, "P"), Recorder()).await()

    assertEquals(RunStatus.SUCCEEDED, next.status)
  }

  @Test
  fun `reclaimed run class loaders are counted`() {
    val runner = runner {}
    val jar = jar("loaders")
    repeat(3) { runner.start(RunRequest("run-$it", jar, "P"), Recorder()).await() }

    val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
    while (runner.loaderStats().reclaimed < 3 && System.nanoTime() < deadline) {
      System.gc()
      Thread.sleep(50)
    }

    assertEquals(LoaderStats(created = 3, reclaimed = 3), runner.loaderStats())
  }
}
