package dev.lawlan.runline.engine.view

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** One read going on under the limit of its resource; closing it gives the place back. */
fun interface ViewPermit : AutoCloseable {
  override fun close()
}

/**
 * How many view reads each resource has going at once (ADR-033, ADR-035). It is a limit of its own:
 * a read takes no place of a run (the capacity of the resource) and a run takes none of these, so
 * views can neither starve runs nor be starved by them. A read beyond the limit is not queued.
 */
class ViewGate(private val limit: Int) {
  private val going = ConcurrentHashMap<String, AtomicInteger>()

  /** A place for one more read of [resource], or null when all its places are taken. */
  fun enter(resource: String): ViewPermit? {
    val count = going.computeIfAbsent(resource) { AtomicInteger() }
    if (count.incrementAndGet() > limit) {
      count.decrementAndGet()
      return null
    }
    return ViewPermit { count.decrementAndGet() }
  }
}
