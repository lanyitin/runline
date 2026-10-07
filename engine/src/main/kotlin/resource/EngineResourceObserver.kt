package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.accessors.Invalidation
import dev.lawlan.runline.accessors.ResourceObserver
import dev.lawlan.runline.core.ResourceFailure
import dev.lawlan.runline.engine.run.RunTelemetry
import io.opentelemetry.api.trace.StatusCode
import java.util.UUID
import org.slf4j.LoggerFactory

/**
 * What the Engine does with what happens on one run's accessors: a span in the run's trace per
 * operation, a line in the run's log for what concerns the run, and the whole story, original
 * exception included, in the Engine's log under the errorId the run was given. Nothing here holds a
 * path or content.
 */
internal class EngineResourceObserver(
    private val runId: UUID,
    private val pipeline: String,
    private val traces: RunTelemetry,
) : ResourceObserver {
  private val log = LoggerFactory.getLogger(EngineResourceObserver::class.java)

  /** Where lines for the run's own log go; set when the run is started. */
  @Volatile var runLog: (String) -> Unit = {}

  override fun <T> operation(resource: String, type: String, operation: String, body: () -> T): T {
    val span = traces.resourceOperation(runId, resource, type, operation)
    try {
      return body()
    } catch (e: Throwable) {
      span?.setStatus(StatusCode.ERROR)
      throw e
    } finally {
      span?.end()
    }
  }

  override fun failed(
      resource: String,
      type: String,
      operation: String,
      failure: ResourceFailure,
      errorId: String?,
      cause: Throwable?,
  ) {
    log.warn(
        "Run {} (pipeline {}): {} on shared resource {} ({}) failed: {}{}",
        runId,
        pipeline,
        operation,
        resource,
        type,
        failure,
        errorId?.let { " errorId=$it" } ?: "",
        cause,
    )
    runLog(
        "[資源] $resource（$type）的 $operation 失敗：$failure" + (errorId?.let { "（errorId $it）" } ?: "")
    )
  }

  override fun invalidated(resource: String, type: String, reason: Invalidation) {
    log.info(
        "Run {} (pipeline {}): the accessor of shared resource {} ({}) is invalid: {}",
        runId,
        pipeline,
        resource,
        type,
        reason,
    )
    // The run's log is closed when the run has ended; only what happens during it is written there.
    if (reason == Invalidation.FORCE_RELEASED) {
      runLog("[資源] $resource（$type）的存取端已失效：管理員強制釋放，之後的操作都會失敗。")
    }
  }
}
