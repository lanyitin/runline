package dev.lawlan.runline.engine.artifact

import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.run.DefinitionDeletedException
import dev.lawlan.runline.engine.run.NewRun
import dev.lawlan.runline.engine.run.RunSource
import dev.lawlan.runline.engine.run.RunState
import dev.lawlan.runline.engine.support.RetentionData
import dev.lawlan.runline.engine.support.StoredPipelines
import dev.lawlan.runline.engine.support.migratedDatabase
import dev.lawlan.runline.engine.trigger.FiringOutcome
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.*

/**
 * Deleting a version together with its runs and impact preview (WI-59, ADR-047), against a real
 * PostgreSQL with the real migrations.
 */
class VersionDeletionStoreTest {
  private val dataSource = dataSourceOf(migratedDatabase())
  private val now = Instant.parse("2026-10-05T12:00:00Z").truncatedTo(ChronoUnit.MICROS)
  private val data = RetentionData(dataSource, now)
  private val artifacts = data.artifacts
  private val hash = data.hash
  private val day = Duration.ofDays(1)
  private val pipelines = StoredPipelines(artifacts, Files.createTempDirectory("version-deletion"))

  private fun count(table: String) = data.count(table)

  private fun sql(statement: String) =
      dataSource.connection.use { c -> c.createStatement().use { it.execute(statement) } }

  @Test
  fun `the impact counts runs by state, their range, the log, and the triggers`() {
    data.run(RunState.SUCCEEDED, endedAt = now - Duration.ofDays(3), logLines = 4)
    data.run(RunState.FAILED, endedAt = now - Duration.ofDays(2), logLines = 1)
    data.run(RunState.SUCCEEDED, endedAt = now - day)
    data.run(RunState.RUNNING)
    data.webhook("hook")
    data.cron("tick")

    val impact = assertNotNull(artifacts.deletionImpact(hash, "alice"))

    assertEquals(4, impact.runs.total)
    assertEquals(mapOf("SUCCEEDED" to 2L, "FAILED" to 1L, "RUNNING" to 1L), impact.runs.byState)
    assertEquals(1, impact.runs.active)
    assertEquals(now - Duration.ofDays(4), impact.runs.earliestAt)
    assertEquals(now - Duration.ofDays(1) - day, impact.runs.latestAt)
    assertEquals(5, impact.logEntries)
    assertEquals(
        listOf(TriggerReference("hook", "webhook"), TriggerReference("tick", "cron")),
        impact.triggers,
    )
    assertTrue(impact.contentRemoved)
    assertFalse(impact.deletable)
  }

  @Test
  fun `a version without runs or triggers is deletable and has no range`() {
    val impact = assertNotNull(artifacts.deletionImpact(hash, "alice"))

    assertEquals(0, impact.runs.total)
    assertEquals(emptyMap(), impact.runs.byState)
    assertNull(impact.runs.earliestAt)
    assertNull(impact.runs.latestAt)
    assertEquals(0, impact.logEntries)
    assertTrue(impact.deletable)
  }

  @Test
  fun `the jar stays when another uploader has a version of it, and their runs are not counted`() {
    pipelines.save("v1", "nightly", uploader = "bob")
    data.run(RunState.SUCCEEDED)
    val bobsDefinition = checkNotNull(data.definitions.find(hash, "bob", "nightly")).id
    data.run(RunState.SUCCEEDED, definitionId = bobsDefinition)
    data.run(RunState.SUCCEEDED, definitionId = bobsDefinition)

    val impact = assertNotNull(artifacts.deletionImpact(hash, "alice"))

    assertFalse(impact.contentRemoved)
    assertEquals(1, impact.runs.total)
  }

  @Test
  fun `there is no impact for a version that does not exist`() {
    assertNull(artifacts.deletionImpact(hash, "carol"))
    assertNull(artifacts.deletionImpact("f".repeat(64), "alice"))
  }

  @Test
  fun `previewing changes nothing`() {
    data.run(RunState.SUCCEEDED, logLines = 2)

    artifacts.deletionImpact(hash, "alice")

    assertEquals(1, count("run"))
    assertEquals(2, count("run_log_entry"))
    assertEquals(1, count("pipeline_artifact"))
  }

  @Test
  fun `a confirmed number of runs deletes the runs, their log, the definitions and the version`() {
    pipelines.save("v1", "nightly", uploader = "bob")
    val bobsDefinition = checkNotNull(data.definitions.find(hash, "bob", "nightly")).id
    val bobsRun = data.run(RunState.SUCCEEDED, definitionId = bobsDefinition, logLines = 3)
    data.run(RunState.SUCCEEDED, logLines = 2)
    data.run(RunState.FAILED, logLines = 1)

    val result = artifacts.delete(hash, "alice", confirmedRuns = 2)

    assertEquals(DeleteResult.Deleted(runs = 2, logEntries = 3), result)
    assertNull(artifacts.find(hash, "alice"))
    assertNotNull(artifacts.find(hash, "bob"))
    assertEquals(1, count("run"))
    assertEquals(3, count("run_log_entry"))
    assertTrue(data.exists(bobsRun))
    assertEquals(1, count("artifact_content"))
  }

  @Test
  fun `the jar goes with the last version when its runs go`() {
    data.run(RunState.SUCCEEDED, logLines = 1)

    artifacts.delete(hash, "alice", confirmedRuns = 1)

    assertEquals(0, count("artifact_content"))
    assertEquals(0, count("pipeline_definition"))
  }

  @Test
  fun `the record of a firing stays and no longer refers to the deleted run`() {
    val run = data.run(RunState.SUCCEEDED)
    val other = pipelines.save("v2", "nightly")
    val trigger =
        data.triggers.insert(
            dev.lawlan.runline.engine.trigger.NewTrigger(
                "hook",
                dev.lawlan.runline.engine.trigger.TriggerKind.WEBHOOK,
                checkNotNull(data.definitions.find(other, "alice", "nightly")).id,
                emptyMap(),
                true,
                null,
                null,
                dev.lawlan.runline.engine.trigger.WebhookSecrets.hash("s"),
                "root",
                now,
            )
        )!!
    val firing = data.delivery(trigger, "d1", now, FiringOutcome.RUN_CREATED, run)

    assertEquals(DeleteResult.Deleted(1, 0), artifacts.delete(hash, "alice", confirmedRuns = 1))

    assertEquals(setOf(firing), data.firingIds(trigger))
    assertNull(data.triggers.firings(trigger.id, 10).single().runId)
  }

  @Test
  fun `ended runs without a confirmation are reported and nothing is deleted`() {
    data.run(RunState.SUCCEEDED, logLines = 2)
    data.run(RunState.FAILED)

    assertEquals(DeleteResult.RunsExist(2), artifacts.delete(hash, "alice"))

    assertEquals(2, count("run"))
    assertEquals(2, count("run_log_entry"))
    assertNotNull(artifacts.find(hash, "alice"))
  }

  @Test
  fun `a confirmation that does not match the runs now is reported with the number now`() {
    data.run(RunState.SUCCEEDED)
    data.run(RunState.SUCCEEDED)

    assertEquals(DeleteResult.ImpactChanged(2), artifacts.delete(hash, "alice", confirmedRuns = 1))
    assertEquals(DeleteResult.ImpactChanged(2), artifacts.delete(hash, "alice", confirmedRuns = 3))
    assertEquals(DeleteResult.ImpactChanged(2), artifacts.delete(hash, "alice", confirmedRuns = 0))

    assertEquals(2, count("run"))
    assertNotNull(artifacts.find(hash, "alice"))
  }

  @Test
  fun `without runs the version is deleted with or without a confirmation of none`() {
    assertEquals(DeleteResult.Deleted(0, 0), artifacts.delete(hash, "alice"))

    val other = pipelines.save("v3", "other")
    assertEquals(DeleteResult.Deleted(0, 0), artifacts.delete(other, "alice", confirmedRuns = 0))
  }

  @Test
  fun `a version that is not there is not found`() {
    assertEquals(DeleteResult.NotFound, artifacts.delete(hash, "carol", confirmedRuns = 0))
  }

  @Test
  fun `triggers stop the deletion and are named, even when the runs are confirmed`() {
    data.run(RunState.SUCCEEDED)
    data.run(RunState.RUNNING)
    data.webhook("hook")

    val result = artifacts.delete(hash, "alice", confirmedRuns = 2)

    assertEquals(DeleteResult.InUse(listOf(TriggerReference("hook", "webhook"))), result)
    assertEquals(2, count("run"))
    assertEquals(1, count("pipeline_trigger"))
  }

  @Test
  fun `runs that have not ended stop the deletion and are counted`() {
    data.run(RunState.SUCCEEDED)
    data.run(RunState.QUEUED)
    data.run(RunState.RUNNING)

    assertEquals(DeleteResult.RunsActive(2), artifacts.delete(hash, "alice", confirmedRuns = 3))

    assertEquals(3, count("run"))
    assertNotNull(artifacts.find(hash, "alice"))
  }

  @Test
  fun `any failure leaves everything as it was`() {
    data.run(RunState.SUCCEEDED, logLines = 2)
    sql("CREATE TABLE test_reference (run_id UUID NOT NULL REFERENCES run (id) ON DELETE RESTRICT)")
    sql("INSERT INTO test_reference SELECT id FROM run")

    val result = artifacts.delete(hash, "alice", confirmedRuns = 1)

    assertIs<DeleteResult.InUse>(result)
    assertEquals(1, count("run"))
    assertEquals(2, count("run_log_entry"))
    assertNotNull(artifacts.find(hash, "alice"))
    assertEquals(1, count("artifact_content"))
  }

  @Test
  fun `a run cannot be created for a definition that was deleted, `() {
    val definitionId = data.definitionId
    artifacts.delete(hash, "alice")

    assertFailsWith<DefinitionDeletedException> {
      data.runs.insert(
          NewRun(UUID.randomUUID(), definitionId, RunSource.Manual("alice"), emptyMap(), now, null)
      )
    }
    assertEquals(0, count("run"))
  }

  @Test
  fun `deleting and creating runs of one version at the same time never leave a run behind`() {
    val pool = Executors.newFixedThreadPool(8)
    try {
      repeat(15) { round ->
        val label = "race-$round"
        val raced = pipelines.save(label, "racer")
        val definitionId = checkNotNull(data.definitions.find(raced, "alice", "racer")).id
        val go = CountDownLatch(1)
        val creators =
            (1..6).map {
              pool.submit<Boolean> {
                go.await()
                try {
                  data.runs.insert(
                      NewRun(
                          UUID.randomUUID(),
                          definitionId,
                          RunSource.Manual("alice"),
                          emptyMap(),
                          now,
                          null,
                      )
                  )
                  true
                } catch (e: DefinitionDeletedException) {
                  false
                }
              }
            }
        val deleter =
            pool.submit<DeleteResult> {
              go.await()
              artifacts.delete(raced, "alice", confirmedRuns = null)
            }
        go.countDown()
        val created = creators.count { it.get() }
        val result = deleter.get()

        if (result is DeleteResult.Deleted) {
          // Deleted means no run existed when it looked, and none was created after.
          assertEquals(0, created, "round $round: runs were created for a deleted version")
          assertEquals(0, data.count("run WHERE definition_id = $definitionId"))
        } else {
          assertNotNull(artifacts.find(raced, "alice"))
          assertEquals(created, data.count("run WHERE definition_id = $definitionId").toInt())
        }
      }
    } finally {
      pool.shutdownNow()
    }
  }
}
