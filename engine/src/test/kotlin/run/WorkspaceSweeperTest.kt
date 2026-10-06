package dev.lawlan.runline.engine.run

import dev.lawlan.runline.runner.RunOutcome
import dev.lawlan.runline.runner.WorkspaceConfig
import dev.lawlan.runline.runner.Workspaces
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.test.*

class WorkspaceSweeperTest {
  private val dir: Path = Files.createTempDirectory("sweeper")
  private val workspaces =
      Workspaces(
          WorkspaceConfig(dir.resolve("shared"), dir.resolve("runs"), 1000, Duration.ofHours(1)),
          Clock.systemUTC(),
      ) {}
  private val sweepers = mutableListOf<WorkspaceSweeper>()

  private fun expiredRunDir(run: String): Path {
    val workspace = workspaces.prepare("nightly", run)
    workspaces.finish("nightly", run, RunOutcome.FAILED)
    Files.setLastModifiedTime(
        workspace.runDir,
        FileTime.from(Instant.now().minus(Duration.ofDays(1))),
    )
    return workspace.runDir
  }

  private fun await(condition: () -> Boolean) {
    val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
    while (!condition()) {
      check(System.nanoTime() < deadline) { "condition not met" }
      Thread.sleep(20)
    }
  }

  @AfterTest fun stop() = sweepers.forEach { it.close() }

  @Test
  fun `directories whose retention has expired are removed as soon as it starts`() {
    val old = expiredRunDir("old")

    WorkspaceSweeper(workspaces, Duration.ofHours(1)).also { sweepers += it }.start()

    await { !Files.exists(old) }
  }

  @Test
  fun `it keeps sweeping on its interval`() {
    WorkspaceSweeper(workspaces, Duration.ofMillis(50)).also { sweepers += it }.start()
    Thread.sleep(150)
    val later = expiredRunDir("later")

    await { !Files.exists(later) }
  }

  @Test
  fun `it stops sweeping when closed`() {
    val sweeper = WorkspaceSweeper(workspaces, Duration.ofMillis(50)).also { sweepers += it }
    sweeper.start()
    sweeper.close()
    val kept = expiredRunDir("kept")

    Thread.sleep(300)

    assertTrue(Files.isDirectory(kept))
  }
}
