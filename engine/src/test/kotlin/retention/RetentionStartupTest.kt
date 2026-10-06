package dev.lawlan.runline.engine.retention

import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.run.RunState
import dev.lawlan.runline.engine.support.RetentionData
import dev.lawlan.runline.engine.support.configureEngine
import dev.lawlan.runline.engine.support.migratedDatabase
import io.ktor.server.testing.*
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.*

/** What the Engine does about retention when it starts and stops, with its real wiring. */
class RetentionStartupTest {
  private val database = migratedDatabase()
  private val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
  private val data = RetentionData(dataSourceOf(database), now)

  private fun causes(e: Throwable) = generateSequence(e) { it.cause }

  private fun sweeperThreads(): Set<Thread> =
      Thread.getAllStackTraces()
          .keys
          .filter { it.name == "retention-sweeper" && it.isAlive }
          .toSet()

  private fun awaitUntil(what: String, condition: () -> Boolean) {
    val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
    while (!condition()) {
      check(System.nanoTime() < deadline) { "not within 30 seconds: $what" }
      Thread.onSpinWait()
    }
  }

  @Test
  fun `the Engine cleans up once as it starts, long before the first interval`() {
    val expired = data.run(RunState.SUCCEEDED, now - Duration.ofDays(400), logLines = 3)
    val fresh = data.run(RunState.SUCCEEDED, now - Duration.ofDays(1), logLines = 3)
    val hook = data.webhook()
    val oldFiring = data.delivery(hook, "old", now - Duration.ofDays(30))
    val freshFiring = data.delivery(hook, "fresh", now - Duration.ofDays(1))

    testApplication {
      // The interval is a day, so what is removed was removed by the pass at start.
      configureEngine(database, mapOf("retention.intervalSeconds" to "86400"))
      startApplication()

      awaitUntil("the expired run is removed") { !data.exists(expired) }
      awaitUntil("the expired firing is removed") { oldFiring !in data.firingIds(hook) }
      assertTrue(data.exists(fresh))
      assertEquals(3, data.logLines(fresh))
      assertTrue(freshFiring in data.firingIds(hook))
    }
  }

  @Test
  fun `the limits of the configuration are the ones used`() {
    val shortLived = data.run(RunState.SUCCEEDED, now - Duration.ofHours(3), logLines = 1)
    val longer = data.run(RunState.SUCCEEDED, now - Duration.ofMinutes(30), logLines = 1)

    testApplication {
      configureEngine(
          database,
          mapOf("retention.runSeconds" to "3600", "retention.logSeconds" to "3600"),
      )
      startApplication()

      awaitUntil("the run past one hour is removed") { !data.exists(shortLived) }
      assertTrue(data.exists(longer), "a run inside the configured hour is kept")
    }
  }

  @Test
  fun `the sweeper runs while the Engine runs and is gone when it stops`() {
    val before = sweeperThreads()

    testApplication {
      configureEngine(database)
      startApplication()

      assertEquals(1, (sweeperThreads() - before).size)
    }

    awaitUntil("the sweeper stops with the Engine") { (sweeperThreads() - before).isEmpty() }
  }

  @Test
  fun `the Engine refuses a dedup window below its floor and names the setting`() {
    val e = assertFails {
      testApplication {
        configureEngine(database, mapOf("retention.webhookDedupWindowSeconds" to "60"))
        startApplication()
      }
    }

    assertTrue(
        causes(e).any { it.message?.contains("retention.webhookDedupWindowSeconds") == true },
        "$e",
    )
  }

  @Test
  fun `the Engine refuses a log kept longer than its run and names the setting`() {
    val e = assertFails {
      testApplication {
        configureEngine(
            database,
            mapOf("retention.runSeconds" to "7200", "retention.logSeconds" to "86400"),
        )
        startApplication()
      }
    }

    assertTrue(causes(e).any { it.message?.contains("retention.logSeconds") == true }, "$e")
  }
}
