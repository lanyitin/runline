package dev.lawlan.runline.engine.resource

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes

/** How a run's wait for its resources ended. */
enum class WaitOutcome {
  ACQUIRED,
  TIMED_OUT,
  CANCELLED,
  REFUSED,
}

/** What every metric of a resource is labelled with, and nothing else: its name and its type. */
data class ResourceLabel(val name: String, val type: String)

/** How many runs hold a resource and how many wait for it. */
data class ResourceCounts(val holders: Int, val waiters: Int)

/**
 * Metrics of shared resources (07-nfr-risks): the time runs wait, the time they hold, the queue
 * length and the number of holders, each per resource and labelled with the resource's type
 * (ADR-019); a resource that is not defined has the type [UNDEFINED_TYPE]. A run that gets its
 * resources at once is recorded as having waited zero seconds.
 */
class ResourceTelemetry(openTelemetry: OpenTelemetry) {
  private val meter = openTelemetry.getMeter("runline.resources")
  private val wait =
      meter
          .histogramBuilder("runline.resources.wait.duration")
          .setUnit("s")
          .setDescription("Time a run waited for its shared resources, by how the wait ended")
          .build()
  private val hold =
      meter
          .histogramBuilder("runline.resources.hold.duration")
          .setUnit("s")
          .setDescription("Time a run held a shared resource")
          .build()
  private val forced = meter.counterBuilder("runline.resources.force_released").build()

  private val checks =
      meter
          .counterBuilder("runline.resources.checks")
          .setDescription("Checks of a resource's entity (labelled by name and type only)")
          .build()
  private val checkFailures =
      meter
          .counterBuilder("runline.resources.check.failures")
          .setDescription("Checks of a resource's entity that did not pass")
          .build()

  private val fileOperations =
      meter
          .counterBuilder("runline.resources.file.operations")
          .setDescription("Operations through accessors of file resources (name and type only)")
          .build()
  private val pathCheckFailures =
      meter
          .counterBuilder("runline.resources.file.path_check_failures")
          .setDescription("Looks at the path of a file resource that found it unusable or outside")
          .build()

  fun fileOperation(resource: ResourceLabel) = fileOperations.add(1, resource.attributes())

  fun pathCheckFailed(resource: ResourceLabel) = pathCheckFailures.add(1, resource.attributes())

  /** An administrator's check of [resource] ended; the category of a failure is in the log. */
  fun checked(resource: ResourceLabel, ok: Boolean) {
    checks.add(1, resource.attributes())
    if (!ok) checkFailures.add(1, resource.attributes())
  }

  fun waited(resource: ResourceLabel, outcome: WaitOutcome, seconds: Double) {
    wait.record(seconds, resource.attributes(OUTCOME, outcome.name))
  }

  fun held(resource: ResourceLabel, seconds: Double, forced: Boolean) {
    hold.record(seconds, resource.attributes(HOW, if (forced) "forced" else "released"))
    if (forced) this.forced.add(1, resource.attributes())
  }

  /** Reports the queue length and holders of every resource [snapshot] lists. */
  fun observe(snapshot: () -> Map<ResourceLabel, ResourceCounts>) {
    meter.gaugeBuilder("runline.resources.queue.length").ofLongs().buildWithCallback { m ->
      snapshot().forEach { (label, counts) ->
        m.record(counts.waiters.toLong(), label.attributes())
      }
    }
    meter.gaugeBuilder("runline.resources.holders").ofLongs().buildWithCallback { m ->
      snapshot().forEach { (label, counts) ->
        m.record(counts.holders.toLong(), label.attributes())
      }
    }
  }

  private fun ResourceLabel.attributes(): Attributes = Attributes.of(RESOURCE, name, TYPE, type)

  private fun ResourceLabel.attributes(key: AttributeKey<String>, value: String): Attributes =
      Attributes.of(RESOURCE, name, TYPE, type, key, value)

  companion object {
    /** The type label of a resource that is not defined, so its type is not known. */
    const val UNDEFINED_TYPE = "unknown"

    private val RESOURCE = AttributeKey.stringKey("resource")
    private val TYPE = AttributeKey.stringKey("type")
    private val OUTCOME = AttributeKey.stringKey("outcome")
    private val HOW = AttributeKey.stringKey("how")
  }
}
