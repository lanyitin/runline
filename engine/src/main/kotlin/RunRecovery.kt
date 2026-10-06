package dev.lawlan.runline.engine

import dev.lawlan.runline.engine.run.RunRecovery
import dev.lawlan.runline.engine.run.WorkspaceSweeper
import io.ktor.server.application.*
import io.ktor.server.plugins.di.*

/**
 * At startup, after the schema check: settles the runs a previous process left unfinished (they
 * become interrupted and are never started again) and starts sweeping expired run directories.
 */
fun Application.configureRunRecovery() {
  val recovery: RunRecovery by dependencies
  val sweeper: WorkspaceSweeper by dependencies
  recovery.recover()
  sweeper.start()
}
