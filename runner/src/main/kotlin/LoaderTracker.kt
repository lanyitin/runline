package dev.lawlan.runline.runner

import java.lang.ref.PhantomReference
import java.lang.ref.ReferenceQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Counts run class loaders as they are created and as the garbage collector reclaims them. Holds
 * only phantom references, so tracking never keeps a loader reachable.
 */
internal class LoaderTracker {
  private val queue = ReferenceQueue<ClassLoader>()
  private val live = ConcurrentHashMap.newKeySet<PhantomReference<ClassLoader>>()
  private val created = AtomicLong()
  private val reclaimed = AtomicLong()

  fun track(loader: ClassLoader) {
    created.incrementAndGet()
    live += PhantomReference(loader, queue)
  }

  fun stats(): LoaderStats {
    while (true) {
      val ref = queue.poll() ?: break
      if (live.remove(ref)) reclaimed.incrementAndGet()
    }
    return LoaderStats(created.get(), reclaimed.get())
  }
}
