package dev.lawlan.runline.engine.trigger

import dev.lawlan.runline.accessors.support.TestDirectories
import dev.lawlan.runline.engine.artifact.DeleteResult
import dev.lawlan.runline.engine.artifact.PostgresArtifactStore
import dev.lawlan.runline.engine.artifact.PostgresDefinitionStore
import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.support.StoredPipelines
import dev.lawlan.runline.engine.support.getWithin
import dev.lawlan.runline.engine.support.migratedDatabase
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.*

class PostgresTriggerStoreTest {
  private val dataSource = dataSourceOf(migratedDatabase())
  private val dir: Path = TestDirectories.forThisTest("trigger-store")
  private val artifacts = PostgresArtifactStore(dataSource)
  private val pipelines = StoredPipelines(artifacts, dir)
  private val definitions = PostgresDefinitionStore(dataSource)
  private val store = PostgresTriggerStore(dataSource)
  private val t0 = Instant.now().truncatedTo(ChronoUnit.MICROS)

  private fun definitionId(hash: String, name: String = "nightly") =
      definitions.find(hash, "alice", name)!!.id

  private fun cron(
      name: String = "every-night",
      definitionId: Long,
      expression: String = "0 2 * * *",
      zone: String = "Asia/Taipei",
      parameters: Map<String, String> = mapOf("env" to "prod", "retries" to "3"),
      enabled: Boolean = true,
  ) =
      NewTrigger(
          name,
          TriggerKind.CRON,
          definitionId,
          parameters,
          enabled,
          expression,
          zone,
          null,
          "root",
          t0,
      )

  private fun webhook(
      name: String = "on-push",
      definitionId: Long,
      hash: String = "ab".repeat(32),
  ) =
      NewTrigger(
          name,
          TriggerKind.WEBHOOK,
          definitionId,
          mapOf("env" to "prod"),
          true,
          null,
          null,
          hash,
          "root",
          t0,
      )

  private fun bound(): Pair<String, Long> {
    val hash = pipelines.save("v1", "nightly")
    return hash to definitionId(hash)
  }

  // ---- storing and reading ----

  @Test
  fun `a cron trigger is stored with its binding, parameters, schedule and who made it`() {
    val (hash, id) = bound()

    val stored = store.insert(cron(definitionId = id))!!

    assertEquals("every-night", stored.name)
    assertEquals(TriggerKind.CRON, stored.kind)
    assertEquals(id, stored.definitionId)
    assertEquals(hash, stored.contentHash)
    assertEquals("nightly", stored.pipeline)
    assertEquals(mapOf("env" to "prod", "retries" to "3"), stored.parameters)
    assertTrue(stored.enabled)
    assertEquals("0 2 * * *", stored.cronExpression)
    assertEquals("Asia/Taipei", stored.timeZone)
    assertNull(stored.secretRotatedAt)
    assertEquals("root", stored.createdBy)
    assertEquals(t0, stored.createdAt)
    assertEquals(stored, store.find("every-night"))
  }

  @Test
  fun `a webhook trigger stores only the hash of its secret and shows whether one is set`() {
    val (_, id) = bound()

    val stored = store.insert(webhook(definitionId = id, hash = "cd".repeat(32)))!!

    assertEquals(TriggerKind.WEBHOOK, stored.kind)
    assertEquals(t0, stored.secretRotatedAt)
    assertNull(stored.cronExpression)
    val credential = store.webhookCredential("on-push")!!
    assertEquals("cd".repeat(32), credential.secretHash)
    assertEquals(stored.id, credential.triggerId)
    assertTrue(credential.enabled)
  }

  @Test
  fun `a trigger name can be used once`() {
    val (_, id) = bound()
    assertNotNull(store.insert(cron(definitionId = id)))

    assertNull(store.insert(cron(definitionId = id, expression = "0 3 * * *")))
    assertEquals("0 2 * * *", store.find("every-night")!!.cronExpression)
  }

  @Test
  fun `the credential of a name that is not a webhook is not found`() {
    val (_, id) = bound()
    store.insert(cron(definitionId = id))

    assertNull(store.webhookCredential("every-night"))
    assertNull(store.webhookCredential("nobody"))
  }

  @Test
  fun `triggers are listed by name and the enabled cron ones can be picked out`() {
    val (_, id) = bound()
    store.insert(cron("b-cron", id))
    store.insert(cron("a-off", id, enabled = false))
    store.insert(webhook("c-hook", id))

    assertEquals(listOf("a-off", "b-cron", "c-hook"), store.list().map { it.name })
    assertEquals(listOf("b-cron"), store.enabledCron().map { it.name })
  }

  @Test
  fun `changing a trigger replaces its state and records who and when`() {
    val (hash, id) = bound()
    val other = definitionId(pipelines.save("v2", "nightly"))
    store.insert(cron(definitionId = id))
    val later = t0.plusSeconds(60)

    val changed =
        store.update(
            "every-night",
            TriggerChange(other, mapOf("env" to "stage"), false, "30 4 * * 1", "UTC"),
            "ops",
            later,
        )!!

    assertEquals(other, changed.definitionId)
    assertNotEquals(hash, changed.contentHash)
    assertEquals(mapOf("env" to "stage"), changed.parameters)
    assertFalse(changed.enabled)
    assertEquals("30 4 * * 1", changed.cronExpression)
    assertEquals("UTC", changed.timeZone)
    assertEquals("ops", changed.updatedBy)
    assertEquals(later, changed.updatedAt)
    assertEquals("root", changed.createdBy)
    assertEquals(t0, changed.createdAt)
    assertNull(
        store.update("nobody", TriggerChange(other, emptyMap(), true, null, null), "ops", later)
    )
  }

  @Test
  fun `replacing the secret keeps one hash and moves the rotation time`() {
    val (_, id) = bound()
    store.insert(webhook(definitionId = id, hash = "aa".repeat(32)))
    val later = t0.plusSeconds(60)

    val rotated = store.replaceSecret("on-push", "bb".repeat(32), "ops", later)!!

    assertEquals(later, rotated.secretRotatedAt)
    assertEquals("ops", rotated.updatedBy)
    assertEquals("bb".repeat(32), store.webhookCredential("on-push")!!.secretHash)
    store.insert(cron(definitionId = id))
    assertNull(store.replaceSecret("every-night", "cc".repeat(32), "ops", later))
    assertNull(store.replaceSecret("nobody", "cc".repeat(32), "ops", later))
  }

  @Test
  fun `deleting a trigger removes it and its record of firings`() {
    val (_, id) = bound()
    val trigger = store.insert(webhook(definitionId = id))!!
    store.claimDelivery(trigger.id, "d-1", t0)

    assertTrue(store.delete("on-push"))

    assertNull(store.find("on-push"))
    assertEquals(0, countRows("trigger_firing"))
    assertFalse(store.delete("on-push"))
  }

  // ---- the data model guarantees ----

  @Test
  fun `an artifact a trigger is bound to cannot be deleted until the trigger is deleted`() {
    val (hash, id) = bound()
    store.insert(cron(definitionId = id))

    assertEquals(DeleteResult.InUse, artifacts.delete(hash, "alice"))
    assertNotNull(artifacts.find(hash, "alice"))

    store.delete("every-night")

    assertEquals(DeleteResult.Deleted, artifacts.delete(hash, "alice"))
    assertNull(artifacts.find(hash, "alice"))
  }

  @Test
  fun `a trigger of another version of the pipeline does not keep this version from being deleted`() {
    val (_, id) = bound()
    val v2 = pipelines.save("v2", "nightly")
    store.insert(cron(definitionId = id))

    assertEquals(DeleteResult.Deleted, artifacts.delete(v2, "alice"))
  }

  @Test
  fun `the database refuses a trigger whose columns do not fit its kind`() {
    val (_, id) = bound()
    // A webhook without a secret hash, and a cron without a schedule.
    assertFails { store.insert(webhook(definitionId = id).copy(secretHash = null)) }
    assertFails { store.insert(cron(definitionId = id).copy(cronExpression = null)) }
    assertFails { store.insert(cron(definitionId = id).copy(name = "bad name!")) }
    assertEquals(0, store.list().size)
  }

  // ---- firings ----

  @Test
  fun `a delivery identifier is claimed once per trigger and can be used by another trigger`() {
    val (_, id) = bound()
    val one = store.insert(webhook("one", id))!!
    val two = store.insert(webhook("two", id))!!

    assertNotNull(store.claimDelivery(one.id, "d-1", t0))
    assertNull(store.claimDelivery(one.id, "d-1", t0.plusSeconds(1)))
    assertNotNull(store.claimDelivery(one.id, "d-2", t0))
    assertNotNull(store.claimDelivery(two.id, "d-1", t0))
  }

  @Test
  fun `racing claims of one delivery have exactly one winner`() {
    val (_, id) = bound()
    val trigger = store.insert(webhook(definitionId = id))!!
    val threads = 16
    val pool = Executors.newFixedThreadPool(threads)
    val start = CountDownLatch(1)

    val claims =
        (1..threads).map {
          pool.submit<Long?> {
            start.await()
            store.claimDelivery(trigger.id, "same-delivery", t0)
          }
        }
    start.countDown()
    val results = claims.map { it.getWithin("a claim of the delivery") }
    pool.shutdown()

    assertEquals(1, results.count { it != null }, results.toString())
    assertEquals(1, store.firings(trigger.id, 10).size)
  }

  @Test
  fun `a delivery whose attempt failed unexpectedly can be claimed again but others cannot`() {
    val (_, id) = bound()
    val trigger = store.insert(webhook(definitionId = id))!!
    fun claimThenSettle(delivery: String, outcome: FiringOutcome) {
      val firing = store.claimDelivery(trigger.id, delivery, t0)!!
      store.settle(firing, outcome, "why", null, null)
    }

    claimThenSettle("failed", FiringOutcome.FAILED)
    claimThenSettle("created", FiringOutcome.RUN_CREATED)
    claimThenSettle("refused", FiringOutcome.REFUSED)
    claimThenSettle("interrupted", FiringOutcome.INTERRUPTED)

    assertNotNull(store.claimDelivery(trigger.id, "failed", t0.plusSeconds(5)))
    assertNull(store.claimDelivery(trigger.id, "created", t0.plusSeconds(5)))
    assertNull(store.claimDelivery(trigger.id, "refused", t0.plusSeconds(5)))
    assertNull(store.claimDelivery(trigger.id, "interrupted", t0.plusSeconds(5)))
    assertEquals(
        FiringOutcome.PENDING,
        store.firings(trigger.id, 10).first { it.deliveryId == "failed" }.outcome,
    )
    assertEquals(4, store.firings(trigger.id, 10).size)
  }

  @Test
  fun `a scheduled time of a cron trigger is claimed once`() {
    val (_, id) = bound()
    val trigger = store.insert(cron(definitionId = id))!!
    val at = Instant.parse("2026-10-05T02:00:00Z")

    assertNotNull(store.claimOccurrence(trigger.id, at, t0))
    assertNull(store.claimOccurrence(trigger.id, at, t0.plusSeconds(1)))
    assertNotNull(store.claimOccurrence(trigger.id, at.plusSeconds(86400), t0))
  }

  @Test
  fun `a firing records how it ended and is listed newest first with a limit`() {
    val (_, id) = bound()
    val trigger = store.insert(webhook(definitionId = id))!!
    val run = insertRun(id)
    val created = store.claimDelivery(trigger.id, "d-1", t0)!!
    store.settle(created, FiringOutcome.RUN_CREATED, null, null, run)
    val refused = store.claimDelivery(trigger.id, "d-2", t0.plusSeconds(10))!!
    store.settle(refused, FiringOutcome.REFUSED, "unsafe_not_allowed", "not allowed", null)
    store.claimDelivery(trigger.id, "d-3", t0.plusSeconds(20))

    val listed = store.firings(trigger.id, 10)

    assertEquals(listOf("d-3", "d-2", "d-1"), listed.map { it.deliveryId })
    assertEquals(FiringOutcome.PENDING, listed[0].outcome)
    assertEquals(FiringOutcome.REFUSED, listed[1].outcome)
    assertEquals("unsafe_not_allowed", listed[1].reason)
    assertEquals("not allowed", listed[1].detail)
    assertNull(listed[1].runId)
    assertEquals(FiringOutcome.RUN_CREATED, listed[2].outcome)
    assertEquals(run, listed[2].runId)
    assertEquals(t0, listed[2].firedAt)
    assertEquals(listOf("d-3", "d-2"), store.firings(trigger.id, 2).map { it.deliveryId })
  }

  @Test
  fun `a cron firing shows the scheduled time`() {
    val (_, id) = bound()
    val trigger = store.insert(cron(definitionId = id))!!
    val scheduled = Instant.parse("2026-10-05T02:00:00Z")
    store.claimOccurrence(trigger.id, scheduled, t0.plusSeconds(1))

    val firing = store.firings(trigger.id, 10).single()

    assertEquals(scheduled, firing.scheduledFor)
    assertNull(firing.deliveryId)
    assertEquals(t0.plusSeconds(1), firing.firedAt)
  }

  @Test
  fun `a firing keeps its record when its run is cleaned up`() {
    val (_, id) = bound()
    val trigger = store.insert(webhook(definitionId = id))!!
    val run = insertRun(id)
    store.settle(
        store.claimDelivery(trigger.id, "d-1", t0)!!,
        FiringOutcome.RUN_CREATED,
        null,
        null,
        run,
    )

    dataSource.connection.use { c ->
      c.createStatement().use { it.execute("DELETE FROM run WHERE id = '$run'") }
    }

    val firing = store.firings(trigger.id, 10).single()
    assertEquals(FiringOutcome.RUN_CREATED, firing.outcome)
    assertNull(firing.runId)
  }

  @Test
  fun `firings left pending by an earlier process become interrupted and others stay`() {
    val (_, id) = bound()
    val trigger = store.insert(webhook(definitionId = id))!!
    store.claimDelivery(trigger.id, "pending", t0)
    store.settle(
        store.claimDelivery(trigger.id, "done", t0)!!,
        FiringOutcome.REFUSED,
        "r",
        null,
        null,
    )

    assertEquals(1, store.interruptPending())

    val byDelivery = store.firings(trigger.id, 10).associate { it.deliveryId to it.outcome }
    assertEquals(FiringOutcome.INTERRUPTED, byDelivery["pending"])
    assertEquals(FiringOutcome.REFUSED, byDelivery["done"])
    assertEquals(0, store.interruptPending())
  }

  private fun countRows(table: String): Int =
      dataSource.connection.use { c ->
        c.createStatement().use { s ->
          s.executeQuery("SELECT count(*) FROM $table").use {
            it.next()
            it.getInt(1)
          }
        }
      }

  /** A run row for [definitionId], straight into the table, as a firing's run. */
  private fun insertRun(definitionId: Long): UUID {
    val id = UUID.randomUUID()
    dataSource.connection.use { c ->
      c.createStatement().use {
        it.execute(
            "INSERT INTO run (id, definition_id, state, source_kind, source_name, parameters, " +
                "created_at) VALUES ('$id', $definitionId, 'QUEUED', 'TRIGGER', 'x', '{}', now())"
        )
      }
    }
    return id
  }
}
