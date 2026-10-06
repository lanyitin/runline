package dev.lawlan.runline.engine

import dev.lawlan.runline.engine.retention.RetentionSweeper
import io.ktor.server.application.*
import io.ktor.server.plugins.di.*

/**
 * At startup, after the schema check and the settling of what a previous process left unfinished:
 * starts the clean-up of expired runs, logs and trigger firings. It cleans once right away, in the
 * background, so startup does not wait for a large backlog, and then at its interval.
 */
fun Application.configureRetention() {
  val sweeper: RetentionSweeper by dependencies
  sweeper.start()
}
