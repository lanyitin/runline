package dev.lawlan.runline.engine.artifact

import dev.lawlan.runline.runner.WorkspaceConfig
import dev.lawlan.runline.runner.WorkspaceObserver
import dev.lawlan.runline.runner.Workspaces
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import kotlin.test.*

/**
 * The rule used at upload must be the one the Runner applies when it makes a run's directories,
 * otherwise a name could be uploaded and then fail every run. Both are driven here with the same
 * names against a real [Workspaces] on a real directory.
 */
class PipelineNameRuleTest {
  private val root = Files.createTempDirectory("name-rule")
  private val workspaces =
      Workspaces(
          WorkspaceConfig(root.resolve("shared"), root.resolve("runs"), 1_000, Duration.ZERO),
          Clock.systemUTC(),
          WorkspaceObserver {},
      )
  private val rule = RunnerPipelineNameRule()

  private val names =
      listOf(
          "ok",
          "Nightly-Report_v2.1",
          "...",
          "-",
          "9",
          "",
          ".",
          "..",
          "a/b",
          "a\\b",
          "has space",
          "über",
          "tab\there",
          "line\nbreak",
          "nul\u0000byte",
          "semi;colon",
          "../escape",
      )

  @Test
  fun `the upload rule accepts exactly the names the Runner makes directories for`() {
    for ((i, name) in names.withIndex()) {
      val runnerRefuses =
          try {
            workspaces.prepare(name, "run-$i")
            false
          } catch (e: IllegalArgumentException) {
            true
          }

      assertEquals(
          runnerRefuses,
          rule.violation(name) != null,
          "name: ${name.replace("\n", "\\n")}",
      )
    }
  }

  @Test
  fun `asking whether a name is acceptable creates nothing on disk`() {
    rule.violation("some-pipeline")
    rule.violation("bad name")

    assertFalse(Files.exists(java.nio.file.Path.of("/nonexistent-runline-name-check")))
  }

  @Test
  fun `the explanation names the pipeline and the allowed characters`() {
    val why = rule.violation("bad name")!!

    assertTrue(why.contains("bad name"), why)
    assertTrue(why.contains("letters, digits"), why)
  }
}
