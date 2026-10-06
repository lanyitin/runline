package dev.lawlan.runline.engine.health

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Where the Engine is in its life, for the probes: still starting, or told to stop. The Engine
 * itself moves it on (the end of the startup stages, the signal to stop); the probes only read it.
 */
class EngineLifecycle {
  private val started = AtomicBoolean(false)
  private val shuttingDown = AtomicBoolean(false)

  val isStarted: Boolean
    get() = started.get()

  val isShuttingDown: Boolean
    get() = shuttingDown.get()

  /** The startup stages are done: the Engine does what it is for. */
  fun completeStartup() = started.set(true)

  /** The signal to stop has been received; from now on the Engine is no longer ready. */
  fun beginShutdown() = shuttingDown.set(true)
}

/** Fails from the moment the Engine is told to stop, ahead of the grace time (ADR-018). */
class ShutdownCheck(private val lifecycle: EngineLifecycle) : ReadinessCheck {
  override val name = "shutdown"

  override fun check() =
      if (lifecycle.isShuttingDown) CheckResult.failed("the Engine is shutting down")
      else CheckResult.OK
}

/** Pending until the startup stages are done (the schema check, recovery, the schedulers). */
class StartupCheck(private val lifecycle: EngineLifecycle) : ReadinessCheck {
  override val name = "startup"

  override fun check() =
      if (lifecycle.isStarted) CheckResult.OK else CheckResult(CheckState.PENDING)
}
