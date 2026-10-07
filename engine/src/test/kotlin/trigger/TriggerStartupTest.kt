package dev.lawlan.runline.engine.trigger

import dev.lawlan.runline.engine.artifact.PostgresArtifactStore
import dev.lawlan.runline.engine.artifact.PostgresDefinitionStore
import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.support.StoredPipelines
import dev.lawlan.runline.engine.support.configureEngine
import dev.lawlan.runline.engine.support.migratedDatabase
import io.ktor.client.request.*
import io.ktor.server.testing.*
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.*

/** What the Engine does with triggers when it starts and stops. */
class TriggerStartupTest {
  private val database = migratedDatabase()
  private val dataSource = dataSourceOf(database)
  private val store = PostgresTriggerStore(dataSource)

  private fun schedulerThreads() =
      Thread.getAllStackTraces().keys.filter { it.name == "cron-scheduler" && it.isAlive }

  /** A stopped executor's worker thread ends a moment after the executor reports termination. */
  private fun awaitNoSchedulerThreads(): Boolean {
    val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
    while (schedulerThreads().isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
    return schedulerThreads().isEmpty()
  }

  @Test
  fun `firings a previous process left pending are marked interrupted and not repeated`() {
    val pipelines =
        StoredPipelines(PostgresArtifactStore(dataSource), Files.createTempDirectory("startup"))
    val hash = pipelines.save("v1", "nightly")
    val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
    val trigger =
        store.insert(
            NewTrigger(
                "on-push",
                TriggerKind.WEBHOOK,
                PostgresDefinitionStore(dataSource).find(hash, "alice", "nightly")!!.id,
                mapOf("env" to "prod"),
                true,
                null,
                null,
                WebhookSecrets.hash("s"),
                "root",
                now,
            )
        )!!
    store.claimDelivery(trigger.id, "left-pending", now)

    testApplication {
      configureEngine(database)
      startApplication()

      val firing = store.firings(trigger.id, 10).single()
      assertEquals(FiringOutcome.INTERRUPTED, firing.outcome)
      // The delivery stays claimed: the Engine never makes a second attempt for it.
      assertNull(store.claimDelivery(trigger.id, "left-pending", now))
    }
  }

  @Test
  fun `the cron scheduler runs while the Engine runs and is gone when it stops`() {
    assertTrue(awaitNoSchedulerThreads(), "no scheduler before the test")

    testApplication {
      configureEngine(database)
      startApplication()

      assertEquals(1, schedulerThreads().size)
    }

    assertTrue(awaitNoSchedulerThreads(), "the scheduler stops with the Engine")
  }
}
