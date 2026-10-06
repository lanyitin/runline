package dev.lawlan.runline.engine.run

import dev.lawlan.runline.runner.Workspaces
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/**
 * Removes private run directories whose retention has expired: right away when started, which also
 * clears what an earlier process left, and then every [interval].
 */
class WorkspaceSweeper(private val workspaces: Workspaces, private val interval: Duration) :
    AutoCloseable {
  private val log = LoggerFactory.getLogger(WorkspaceSweeper::class.java)
  private val executor = Executors.newSingleThreadScheduledExecutor { task ->
    Thread.ofPlatform().name("workspace-sweeper").daemon(true).unstarted(task)
  }

  fun start() {
    executor.scheduleWithFixedDelay(::sweep, 0, interval.toMillis(), TimeUnit.MILLISECONDS)
  }

  private fun sweep() {
    try {
      workspaces.sweep()
    } catch (e: Exception) {
      // A failed sweep is tried again next time; it must not end the schedule.
      log.error("Sweeping run directories failed", e)
    }
  }

  override fun close() {
    executor.shutdownNow()
    executor.awaitTermination(5, TimeUnit.SECONDS)
  }
}
