package dev.lawlan.runline.engine

import dev.lawlan.runline.engine.config.EngineConfig
import java.time.Duration

fun main(args: Array<String>) {
  // A platform's SIGTERM ends the JVM with 143; after a graceful stop the process exits with 0.
  Runtime.getRuntime()
      .addShutdownHook(Thread { CleanExit.exitWithZeroOnceStopped(Duration.ofSeconds(120)) })
  io.ktor.server.netty.EngineMain.main(args + shutdownArguments(System.getenv()))
}

/**
 * The server library reads how long it may take to stop before the application, and so the
 * configuration, exists. The one grace time for shutting down is `RUNLINE_SHUTDOWN_GRACE_SECONDS`,
 * so it is handed over here, in the milliseconds the library wants. A value that is not a valid
 * number is left out: the configuration check at startup reports it.
 */
internal fun shutdownArguments(env: Map<String, String>): Array<String> {
  val seconds =
      env["RUNLINE_SHUTDOWN_GRACE_SECONDS"]
          ?.trim()
          ?.takeIf { it.isNotEmpty() }
          ?.let { it.toLongOrNull()?.takeIf { n -> n >= 0 } ?: return emptyArray() }
          ?: EngineConfig.DEFAULT_SHUTDOWN_GRACE_SECONDS
  return arrayOf("-P:ktor.deployment.shutdownTimeout=${seconds * 1000}")
}
