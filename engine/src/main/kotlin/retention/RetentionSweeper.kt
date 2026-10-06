package dev.lawlan.runline.engine.retention

import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/**
 * Runs the retention clean-up in the background: once as soon as it starts, which also clears what
 * accumulated while no Engine ran, and then every [interval] after the previous pass ended, so
 * passes never overlap. It does not delay startup; the first pass runs on its own thread.
 */
class RetentionSweeper(private val pass: () -> Unit, private val interval: Duration) :
    AutoCloseable {
  private val log = LoggerFactory.getLogger(RetentionSweeper::class.java)
  private val executor = Executors.newSingleThreadScheduledExecutor { task ->
    Thread.ofPlatform().name(THREAD_NAME).daemon(true).unstarted(task)
  }

  fun start() {
    executor.scheduleWithFixedDelay(::run, 0, interval.toMillis(), TimeUnit.MILLISECONDS)
  }

  private fun run() {
    try {
      pass()
    } catch (e: Exception) {
      // A failed pass is tried again next time; it must not end the schedule.
      log.error("Retention clean-up failed", e)
    }
  }

  override fun close() {
    executor.shutdownNow()
    executor.awaitTermination(5, TimeUnit.SECONDS)
  }

  companion object {
    const val THREAD_NAME = "retention-sweeper"
  }
}
