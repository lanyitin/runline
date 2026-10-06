package dev.lawlan.runline.engine

import dev.lawlan.runline.engine.health.EngineLifecycle
import io.ktor.server.application.*
import io.ktor.server.plugins.di.*

/**
 * The last of the startup stages: everything before it (the schema check, run recovery, the
 * settling of triggers, the schedulers) is done, which is what the `startup` probe waits for. Keep
 * it last in `application.yaml`.
 */
fun Application.completeStartup() {
  val lifecycle: EngineLifecycle by dependencies
  lifecycle.completeStartup()
}
