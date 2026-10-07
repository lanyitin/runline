package dev.lawlan.runline.engine.allowlist

import dev.lawlan.runline.analyzer.AllowListEntry
import dev.lawlan.runline.analyzer.SafetyAnalyzer
import dev.lawlan.runline.analyzer.Verdict
import dev.lawlan.runline.engine.auth.ApiIdentity
import dev.lawlan.runline.engine.auth.Role
import dev.lawlan.runline.engine.config.InitialAllowList
import dev.lawlan.runline.engine.run.CreateRunResult
import dev.lawlan.runline.engine.run.RunState
import dev.lawlan.runline.engine.support.RunHarness
import dev.lawlan.runline.engine.support.RunHarness.Companion.holdUntilReleased
import io.opentelemetry.api.OpenTelemetry
import java.nio.file.Files
import java.time.Clock
import kotlin.test.*

/** A change of the allow list leaves a run that is already going alone (WI-10). */
class AllowListRunIsolationTest {
  private val harness = RunHarness()
  private val list =
      listOf("java.lang", "java.util", "kotlin", "org.jetbrains.annotations").map {
        AllowListEntry(it)
      }
  private val admin =
      AllowListAdmin(
          PostgresAllowListStore(harness.dataSource),
          SafetyAnalyzer(),
          Clock.systemUTC(),
          AllowListTelemetry(OpenTelemetry.noop()),
          Files.createTempDirectory(harness.dir, "scratch"),
      )

  @AfterTest fun close() = harness.close()

  @Test
  fun `a run in progress finishes although the change makes its pipeline unsafe, a new run is refused`() {
    val hash = harness.upload("held", holdUntilReleased("held-started"))
    // The harness judges uploads with a fixed list; the administered list starts from the same.
    admin.initialize(InitialAllowList(list, fromConfiguration = true))
    val runId = harness.start(hash, "held")
    harness.awaitFile(harness.shared("held", "held-started"))
    assertEquals(RunState.RUNNING, harness.state(runId))

    val result =
        admin.change(
            AllowListChange.Remove(EntryKind.PACKAGE, "java.lang"),
            ApiIdentity("root", Role.ADMIN),
        )

    val flipped = assertIs<ChangeResult.Applied>(result).impact.changes.single()
    assertEquals(Verdict.UNSAFE, flipped.to)
    assertEquals(
        Verdict.UNSAFE,
        harness.artifacts.find(hash, "alice")!!.definitions.single().verdict,
    )
    assertEquals(RunState.RUNNING, harness.state(runId), "the run is not touched")
    assertIs<CreateRunResult.UnsafeNotAllowed>(harness.create(hash, "held"))

    Files.writeString(harness.shared("held", "release"), "x")

    val ended = harness.awaitEnd(runId)
    assertEquals(RunState.SUCCEEDED, ended.state)
    assertNull(ended.unsafeExecution)
  }
}
