package dev.lawlan.runline.engine.trigger

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/**
 * Fires the enabled cron triggers at their scheduled times (ADR-005), for a single Engine instance.
 *
 * What is due is worked out from the triggers in the database and the [clock] alone, so a restart
 * needs no recovery: the enabled triggers simply go on from the time the new process started, and
 * the times missed while no process ran are not made up. While running, a scheduled time is fired
 * when it is no older than [GRACE] (a slow tick is not a missed time), and when several are due
 * only the latest fires. A time is fired once: it is claimed in the database first, which also
 * stops a second process, or a second tick, from firing it again.
 *
 * [tick] does the work and is what the tests call with a clock they control; [start] only calls it
 * regularly in the background.
 */
class CronScheduler(
    private val triggers: TriggerStore,
    private val firer: TriggerFirer,
    private val clock: Clock,
) : AutoCloseable {
  private val log = LoggerFactory.getLogger(CronScheduler::class.java)
  private val executor = Executors.newSingleThreadScheduledExecutor { task ->
    Thread.ofPlatform().name("cron-scheduler").daemon(true).unstarted(task)
  }

  /** Everything up to here has been looked at; nothing before it is ever fired. */
  private var evaluatedUntil: Instant = clock.instant()
  private var evaluatedMinute: Instant = evaluatedUntil.truncatedTo(ChronoUnit.MINUTES)

  /**
   * Fires what is due at the clock's current time. Schedules have a resolution of a minute, so the
   * triggers are only looked at once in each minute, unless something went wrong in the last look,
   * which is then repeated.
   */
  @Synchronized
  fun tick() {
    val now = clock.instant()
    val minute = now.truncatedTo(ChronoUnit.MINUTES)
    if (minute == evaluatedMinute) return
    val candidates =
        try {
          triggers.enabledCron()
        } catch (e: Exception) {
          log.error("Cron triggers could not be read; trying again", e)
          return
        }
    val earliest = maxOf(evaluatedUntil, now.minus(GRACE))
    var complete = true
    for (trigger in candidates) {
      try {
        fireIfDue(trigger, earliest, now)
      } catch (e: Exception) {
        complete = false
        log.error("Cron trigger {} could not be evaluated; trying again", trigger.name, e)
      }
    }
    if (complete) {
      evaluatedUntil = now
      evaluatedMinute = minute
    }
  }

  private fun fireIfDue(trigger: Trigger, earliest: Instant, now: Instant) {
    val schedule = CronSchedule.parse(checkNotNull(trigger.cronExpression), trigger.timeZone)
    // A change to a trigger starts it afresh: what it would have fired before then is not due.
    val due = schedule.latestIn(maxOf(earliest, trigger.updatedAt), now) ?: return
    val firing = triggers.claimOccurrence(trigger.id, due, now) ?: return
    firer.fire(trigger, firing)
  }

  /** Starts evaluating the schedules in the background, every second. */
  fun start() {
    executor.scheduleWithFixedDelay(
        {
          try {
            tick()
          } catch (e: Exception) {
            // A failed tick is tried again next time; it must not end the schedule.
            log.error("Cron scheduling failed", e)
          }
        },
        1,
        1,
        TimeUnit.SECONDS,
    )
  }

  override fun close() {
    executor.shutdownNow()
    executor.awaitTermination(5, TimeUnit.SECONDS)
  }

  private companion object {
    /** How late a scheduled time may still be fired. */
    val GRACE: Duration = Duration.ofMinutes(1)
  }
}
