package dev.lawlan.runline.engine.resource

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * What an administrator sees of the use of a resource right now, by type: the requests an
 * `openai-compatible` resource has in flight, the connections a `jdbc-pool` resource has in use.
 * What does not belong to the type is null.
 */
data class ResourceUsage(val inFlightRequests: Int? = null, val activeConnections: Int? = null)

/**
 * How many requests each `openai-compatible` resource has in flight at this moment, across all
 * runs: what the resource's view shows. Kept in memory, like the capacity (ADR-007).
 */
class OpenAiUsage {
  private val inFlight = ConcurrentHashMap<String, AtomicInteger>()

  fun inFlight(resource: String): Int = inFlight[resource]?.get() ?: 0

  internal fun started(resource: String) {
    inFlight.computeIfAbsent(resource) { AtomicInteger() }.incrementAndGet()
  }

  internal fun finished(resource: String) {
    inFlight[resource]?.decrementAndGet()
  }
}
