package dev.lawlan.runline.engine

import ch.qos.logback.classic.LoggerContext
import dev.lawlan.runline.engine.config.EngineConfig
import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import org.slf4j.LoggerFactory

/**
 * Gives log lines the Engine's name, the same one traces and metrics carry. `logback.xml` prints
 * the `service.name` property, which is set here from the configuration; lines written before the
 * configuration is read (the framework's first few) have none.
 */
fun Application.configureLogging() {
  val config: EngineConfig by dependencies
  (LoggerFactory.getILoggerFactory() as LoggerContext).putProperty(
      "service.name",
      config.telemetry.serviceName,
  )
}
