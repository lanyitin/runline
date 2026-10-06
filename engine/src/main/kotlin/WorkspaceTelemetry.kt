package dev.lawlan.runline.engine

import dev.lawlan.runline.runner.WorkspaceEvent
import dev.lawlan.runline.runner.WorkspaceObserver
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import org.slf4j.LoggerFactory

/** Reports workspace directory events as log lines and OpenTelemetry metrics. */
class WorkspaceTelemetry(openTelemetry: OpenTelemetry) : WorkspaceObserver {
  private val log = LoggerFactory.getLogger(WorkspaceTelemetry::class.java)
  private val meter = openTelemetry.getMeter("runline.workspace")
  private val created = meter.counterBuilder("runline.workspace.directories.created").build()
  private val removed = meter.counterBuilder("runline.workspace.directories.removed").build()
  private val usage = meter.gaugeBuilder("runline.workspace.shared.usage.bytes").ofLongs().build()

  override fun onEvent(event: WorkspaceEvent) {
    when (event) {
      is WorkspaceEvent.Created -> {
        created.add(1, scope(event.scope.name))
        log.info(
            "Created {} directory for pipeline {} run {}",
            event.scope,
            event.pipeline,
            event.runId,
        )
      }
      is WorkspaceEvent.Removed -> {
        removed.add(1, scope(event.scope.name))
        log.info(
            "Removed {} directory for pipeline {} run {}",
            event.scope,
            event.pipeline,
            event.runId,
        )
      }
      is WorkspaceEvent.SharedUsage -> {
        usage.set(event.bytes, Attributes.of(AttributeKey.stringKey("pipeline"), event.pipeline))
        log.info("Shared directory of pipeline {} uses {} bytes", event.pipeline, event.bytes)
      }
    }
  }

  private fun scope(name: String) = Attributes.of(AttributeKey.stringKey("scope"), name)
}
