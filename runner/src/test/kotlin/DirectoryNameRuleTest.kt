package dev.lawlan.runline.runner

import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

/**
 * The public rule must say exactly what [Workspaces.prepare] does, and asking must not touch the
 * disk. Both are driven with the same names.
 */
class DirectoryNameRuleTest {
  @TempDir lateinit var tmp: Path

  private val names =
      listOf(
          "ok",
          "Nightly-Report_v2.1",
          "...",
          "-",
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
          "../escape",
      )

  private fun workspaces() =
      Workspaces(
          WorkspaceConfig(tmp.resolve("shared"), tmp.resolve("runs"), 1_000, Duration.ZERO),
          Clock.systemUTC(),
          WorkspaceObserver {},
      )

  private fun refusal(block: () -> Unit): String? =
      try {
        block()
        null
      } catch (e: IllegalArgumentException) {
        e.message
      }

  @Test
  fun `a pipeline name is judged as prepare judges it, with the same explanation`() {
    for ((i, name) in names.withIndex()) {
      val fromPrepare = refusal { workspaces().prepare(name, "run-$i") }

      assertEquals(
          fromPrepare,
          DirectoryNameRule.pipelineViolation(name),
          "name: ${name.replace("\n", "\\n")}",
      )
    }
  }

  @Test
  fun `a run id is judged as prepare judges it, with the same explanation`() {
    for (id in names) {
      val fromPrepare = refusal { workspaces().prepare("pipe", id) }

      assertEquals(
          fromPrepare,
          DirectoryNameRule.runIdViolation(id),
          "id: ${id.replace("\n", "\\n")}",
      )
    }
  }

  @Test
  fun `the explanation names the value and the allowed characters`() {
    assertEquals(
        "Invalid pipeline 'bad name': only letters, digits, '.', '_' and '-' are allowed",
        DirectoryNameRule.pipelineViolation("bad name"),
    )
    assertNull(DirectoryNameRule.pipelineViolation("fine"))
  }

  @Test
  fun `asking creates nothing on disk`() {
    DirectoryNameRule.pipelineViolation("some-pipeline")
    DirectoryNameRule.runIdViolation("some-run")

    assertTrue(tmp.toFile().list().isNullOrEmpty())
  }
}
