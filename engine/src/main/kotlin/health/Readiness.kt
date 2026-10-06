package dev.lawlan.runline.engine.health

import org.slf4j.LoggerFactory

/** What one check found. */
enum class CheckState(val text: String) {
  OK("ok"),
  FAILED("failed"),
  PENDING("pending"),
}

/**
 * A check's answer. [reason] is for the log only; it never reaches a response (ADR-018: no cause of
 * a failure is told to a caller who needs no token).
 */
class CheckResult(val state: CheckState, val reason: String? = null) {
  companion object {
    val OK = CheckResult(CheckState.OK)

    fun failed(reason: String) = CheckResult(CheckState.FAILED, reason)
  }
}

/** One thing that has to be in order for the Engine to be ready, under its fixed name. */
interface ReadinessCheck {
  val name: String

  fun check(): CheckResult
}

/** Where the Engine stands: ready, not ready, or not ready because it is stopping. */
enum class ReadinessStatus(val text: String) {
  READY("ready"),
  NOT_READY("not_ready"),
  SHUTTING_DOWN("shutting_down"),
}

/** Ready, or why not as far as a caller may be told. */
class ReadinessReport(val status: ReadinessStatus, val checks: Map<String, CheckState>)

/**
 * Runs every check; the Engine is ready when all of them are in order. While it is stopping it is
 * not ready, and says so.
 */
class Readiness(
    private val checks: List<ReadinessCheck>,
    private val lifecycle: EngineLifecycle,
    private val telemetry: HealthTelemetry,
) {
  private val log = LoggerFactory.getLogger("dev.lawlan.runline.engine.health.Readiness")

  fun evaluate(): ReadinessReport {
    val results = checks.associate { check ->
      val result =
          try {
            check.check()
          } catch (e: Exception) {
            CheckResult.failed(e.toString())
          }
      if (result.state == CheckState.FAILED) {
        log.warn("Readiness check {} failed: {}", check.name, result.reason)
        telemetry.failed(check.name)
      }
      check.name to result.state
    }
    val status =
        when {
          lifecycle.isShuttingDown -> ReadinessStatus.SHUTTING_DOWN
          results.values.all { it == CheckState.OK } -> ReadinessStatus.READY
          else -> ReadinessStatus.NOT_READY
        }
    return ReadinessReport(status, results)
  }
}
