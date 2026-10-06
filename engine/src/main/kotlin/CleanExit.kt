package dev.lawlan.runline.engine

import io.ktor.server.application.*
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The exit code of a graceful shutdown. A JVM ended by SIGTERM exits with 143, which a platform
 * (systemd) takes for a failure even though the Engine stopped as asked. When the Engine has been
 * serving and has finished stopping, the process exits with 0; any other end (a start that failed,
 * a stop that did not finish) keeps the code the JVM gives it, so failures stay non-zero.
 */
internal object CleanExit {
  private val serving = AtomicBoolean(false)
  private val stopped = CountDownLatch(1)

  /** Called by the application when it is up and when it has stopped. */
  fun watch(application: Application) {
    application.monitor.subscribe(ApplicationStarted) { serving.set(true) }
    application.monitor.subscribe(ApplicationStopped) { stopped.countDown() }
  }

  /**
   * Run as a JVM shutdown hook: waits up to [patience] for the application to have stopped and then
   * ends the process with 0. Does nothing if the application never served.
   */
  fun exitWithZeroOnceStopped(patience: Duration) {
    if (!serving.get()) return
    if (stopped.await(patience.toMillis(), TimeUnit.MILLISECONDS)) Runtime.getRuntime().halt(0)
  }
}

/** Lets [CleanExit] see the application start and stop. */
fun Application.configureCleanExit() = CleanExit.watch(this)
