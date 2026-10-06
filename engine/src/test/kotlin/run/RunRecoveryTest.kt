package dev.lawlan.runline.engine.run

import dev.lawlan.runline.engine.artifact.PostgresArtifactStore
import dev.lawlan.runline.engine.artifact.PostgresDefinitionStore
import dev.lawlan.runline.engine.artifact.Visibility
import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.support.StoredPipelines
import dev.lawlan.runline.engine.support.migratedDatabase
import dev.lawlan.runline.runner.WorkspaceConfig
import dev.lawlan.runline.runner.Workspaces
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*

class RunRecoveryTest {
  private val dir: Path = Files.createTempDirectory("recovery")
  private val dataSource = dataSourceOf(migratedDatabase())
  private val pipelines = StoredPipelines(PostgresArtifactStore(dataSource), dir)
  private val definitions = PostgresDefinitionStore(dataSource)
  private val store = PostgresRunStore(dataSource)

  private class TestClock(var now: Instant = Instant.parse("2026-10-04T00:00:00Z")) : Clock() {
    override fun getZone() = ZoneOffset.UTC

    override fun withZone(zone: java.time.ZoneId?) = this

    override fun instant(): Instant = now
  }

  private val clock = TestClock()
  private val retention = Duration.ofHours(1)
  private val workspaces =
      Workspaces(
          WorkspaceConfig(dir.resolve("shared"), dir.resolve("runs"), 1000, retention),
          clock,
      ) {}
  private val recovery = RunRecovery(store, workspaces, clock)

  private fun running(name: String): Pair<UUID, Path> {
    val hash = pipelines.save("v-$name", name)
    val run =
        store.insert(
            NewRun(
                UUID.randomUUID(),
                definitions.find(hash, name)!!.id,
                RunSource.Manual("alice"),
                emptyMap(),
                clock.instant(),
                null,
            )
        )
    store.advance(run.id, RunState.RUNNING, clock.instant())
    val workspace = workspaces.prepare(name, run.id.toString())
    return run.id to workspace.runDir
  }

  @Test
  fun `runs left unfinished are marked interrupted`() {
    val (id, _) = running("nightly")
    val queued =
        store
            .insert(
                NewRun(
                    UUID.randomUUID(),
                    definitions.find(pipelines.save("v2", "other"), "other")!!.id,
                    RunSource.Manual("alice"),
                    emptyMap(),
                    clock.instant(),
                    null,
                )
            )
            .id

    assertEquals(2, recovery.recover())

    assertEquals(RunState.INTERRUPTED, store.find(id, Visibility.All)!!.state)
    assertEquals(RunState.INTERRUPTED, store.find(queued, Visibility.All)!!.state)
    assertEquals(0, recovery.recover(), "nothing is left to settle the second time")
  }

  @Test
  fun `runs that ended are left as they were`() {
    val (id, _) = running("nightly")
    store.finish(id, RunState.SUCCEEDED, clock.instant())

    assertEquals(0, recovery.recover())

    assertEquals(RunState.SUCCEEDED, store.find(id, Visibility.All)!!.state)
  }

  @Test
  fun `the private directory of an interrupted run is kept for the retention period and then swept`() {
    val (_, runDir) = running("nightly")
    clock.now = clock.now.plus(Duration.ofDays(2)) // the run dir's age at restart is long past
    Files.setLastModifiedTime(
        runDir,
        java.nio.file.attribute.FileTime.from(Instant.parse("2026-10-04T00:00:00Z")),
    )

    recovery.recover()
    workspaces.sweep()
    assertTrue(
        Files.isDirectory(runDir),
        "retention counts from the interruption, not from the old start",
    )

    clock.now = clock.now.plus(retention).plusSeconds(1)
    workspaces.sweep()
    assertFalse(Files.exists(runDir))
  }

  @Test
  fun `one run whose directory cannot be settled does not keep the others from being settled`() {
    val hash = pipelines.save("v-bad", "bad name")
    val bad =
        store.insert(
            NewRun(
                UUID.randomUUID(),
                definitions.find(hash, "bad name")!!.id,
                RunSource.Manual("alice"),
                emptyMap(),
                clock.instant(),
                null,
            )
        )
    val (good, _) = running("nightly")

    assertEquals(2, recovery.recover())

    assertEquals(RunState.INTERRUPTED, store.find(bad.id, Visibility.All)!!.state)
    assertEquals(RunState.INTERRUPTED, store.find(good, Visibility.All)!!.state)
  }
}
