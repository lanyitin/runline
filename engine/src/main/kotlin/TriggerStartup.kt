package dev.lawlan.runline.engine

import dev.lawlan.runline.engine.trigger.CronScheduler
import dev.lawlan.runline.engine.trigger.TriggerStore
import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import org.slf4j.LoggerFactory

/**
 * At startup, after the schema check and run recovery: settles the firings a previous process left
 * unfinished (they are marked interrupted and never repeated by the Engine) and starts the cron
 * scheduler. Cron triggers that are enabled need nothing else to resume: the scheduler works from
 * the database, from the time it starts.
 */
fun Application.configureTriggers() {
  val triggers: TriggerStore by dependencies
  val scheduler: CronScheduler by dependencies
  val interrupted = triggers.interruptPending()
  if (interrupted > 0) {
    LoggerFactory.getLogger("dev.lawlan.runline.engine.TriggerStartup")
        .warn("{} trigger firings were left unfinished by the previous process", interrupted)
  }
  scheduler.start()
}
