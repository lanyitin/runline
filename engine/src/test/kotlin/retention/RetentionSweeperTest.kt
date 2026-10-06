package dev.lawlan.runline.engine.retention

import dev.lawlan.runline.engine.config.RetentionSettings
import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.run.RunState
import dev.lawlan.runline.engine.support.MutableClock
import dev.lawlan.runline.engine.support.RetentionData
import dev.lawlan.runline.engine.support.migratedDatabase
import io.opentelemetry.api.OpenTelemetry
import java.time.Duration
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** The schedule of the clean-up: once at start, then at its interval, whatever a pass does. */
class RetentionSweeperTest {
  private val sweepers = mutableListOf<RetentionSweeper>()
  private val finished = Semaphore(0)

  @AfterTest fun stop() = sweepers.forEach { it.close() }

  private fun sweeper(interval: Duration, pass: () -> Unit) =
      RetentionSweeper(
              {
                try {
                  pass()
                } finally {
                  finished.release()
                }
              },
              interval,
          )
          .also { sweepers += it }

  /** Waits for [passes] more passes to finish; the wait ends when they have, not after a delay. */
  private fun awaitPasses(passes: Int) {
    assertTrue(finished.tryAcquire(passes, 30, TimeUnit.SECONDS), "fewer than $passes passes")
  }

  @Test
  fun `it passes once as soon as it starts, long before the first interval`() {
    val count = AtomicInteger()
    sweeper(Duration.ofDays(1)) { count.incrementAndGet() }.start()

    awaitPasses(1)

    assertEquals(1, count.get())
  }

  @Test
  fun `it passes again on its interval`() {
    val count = AtomicInteger()
    sweeper(Duration.ofMillis(10)) { count.incrementAndGet() }.start()

    awaitPasses(4)

    assertTrue(count.get() >= 4)
  }

  @Test
  fun `a pass that fails does not end the schedule`() {
    val count = AtomicInteger()
    sweeper(Duration.ofMillis(10)) {
          if (count.incrementAndGet() == 1) error("the first pass fails")
        }
        .start()

    awaitPasses(3)

    assertTrue(count.get() >= 3)
  }

  @Test
  fun `closing it ends its thread, so nothing runs after`() {
    val others = sweeperThreads()
    val sweeper = sweeper(Duration.ofMillis(10)) {}
    sweeper.start()
    awaitPasses(1)
    assertEquals(1, (sweeperThreads() - others).size)

    sweeper.close()

    // A stopped executor's worker thread ends a moment after the executor reports termination.
    val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
    while ((sweeperThreads() - others).isNotEmpty() && System.nanoTime() < deadline) {
      Thread.onSpinWait()
    }
    assertEquals(emptySet<Thread>(), sweeperThreads() - others)
  }

  private fun sweeperThreads(): Set<Thread> =
      Thread.getAllStackTraces()
          .keys
          .filter { it.name == RetentionSweeper.THREAD_NAME && it.isAlive }
          .toSet()

  @Test
  fun `started with the real clean-up it removes what is expired at once`() {
    val dataSource = dataSourceOf(migratedDatabase())
    val clock = MutableClock()
    val data = RetentionData(dataSource, clock.instant())
    val expired = data.run(RunState.SUCCEEDED, clock.instant() - Duration.ofDays(60), logLines = 2)
    val fresh = data.run(RunState.SUCCEEDED, clock.instant() - Duration.ofDays(1), logLines = 2)
    val cleaner =
        RetentionCleaner(
            PostgresRetentionStore(dataSource),
            RetentionSettings(
                Duration.ofDays(30),
                Duration.ofDays(30),
                Duration.ofDays(7),
                Duration.ofDays(30),
                Duration.ofHours(1),
                1000,
            ),
            clock,
            RetentionTelemetry(OpenTelemetry.noop()),
        )
    sweeper(Duration.ofDays(1)) { cleaner.clean() }.start()

    awaitPasses(1)

    assertFalse(data.exists(expired))
    assertTrue(data.exists(fresh))
  }
}
