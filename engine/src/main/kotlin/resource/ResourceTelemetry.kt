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

/** How many runs hold a resource and how many wait for it. */
data class ResourceCounts(val holders: Int, val waiters: Int)

/**
 * Metrics of shared resources (07-nfr-risks): the time runs wait, the time they hold, the queue
 * length and the number of holders, each per resource. A run that gets its resources at once is
 * recorded as having waited zero seconds.
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

  fun waited(resource: String, outcome: WaitOutcome, seconds: Double) {
    wait.record(seconds, Attributes.of(RESOURCE, resource, OUTCOME, outcome.name))
  }

  fun held(resource: String, seconds: Double, forced: Boolean) {
    hold.record(
        seconds,
        Attributes.of(RESOURCE, resource, HOW, if (forced) "forced" else "released"),
    )
    if (forced) this.forced.add(1, Attributes.of(RESOURCE, resource))
  }

  /** Reports the queue length and holders of every resource [snapshot] lists. */
  fun observe(snapshot: () -> Map<String, ResourceCounts>) {
    meter.gaugeBuilder("runline.resources.queue.length").ofLongs().buildWithCallback { m ->
      snapshot().forEach { (name, counts) ->
        m.record(counts.waiters.toLong(), Attributes.of(RESOURCE, name))
      }
    }
    meter.gaugeBuilder("runline.resources.holders").ofLongs().buildWithCallback { m ->
      snapshot().forEach { (name, counts) ->
        m.record(counts.holders.toLong(), Attributes.of(RESOURCE, name))
      }
    }
  }

  private companion object {
    val RESOURCE = AttributeKey.stringKey("resource")
    val OUTCOME = AttributeKey.stringKey("outcome")
    val HOW = AttributeKey.stringKey("how")
  }
}
