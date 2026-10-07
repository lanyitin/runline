package dev.lawlan.runline.engine.run

import dev.lawlan.runline.engine.secret.SecretMasking
import java.time.Clock
import java.util.UUID
import org.slf4j.LoggerFactory

/**
 * The one place that records a run's state changes: it writes the state, logs it and tells
 * telemetry, and only for a change that really happened (a state never moves backward).
 */
class RunProgress(
    private val runs: RunStore,
    private val telemetry: RunTelemetry,
    private val clock: Clock,
) {
  private val log = LoggerFactory.getLogger(RunProgress::class.java)

  fun advance(id: UUID, state: RunState) {
    if (runs.advance(id, state, clock.instant())) {
      log.info("Run {} is {}", id, state)
      telemetry.entered(id, state)
    }
  }

  fun finish(
      id: UUID,
      state: RunState,
      reported: FailureInfo? = null,
      residualThreads: List<String> = emptyList(),
  ) {
    // What a service echoed back into an exception message must not be kept or logged as it came.
    val failure = reported?.masked()
    if (runs.finish(id, state, clock.instant(), failure)) {
      if (failure == null) log.info("Run {} ended {}", id, state)
      else log.warn("Run {} ended {}: {}: {}", id, state, failure.type, failure.message)
      if (residualThreads.isNotEmpty()) {
        log.warn("Run {} left threads running: {}", id, residualThreads)
      }
      telemetry.ended(id, state, residualThreads)
    }
  }
}

private fun FailureInfo.masked() =
    FailureInfo(type, message?.let(SecretMasking::mask), SecretMasking.mask(trace))
