package dev.lawlan.runline.accessors.openai

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** The quota was aborted: nobody gets a share of it any more. */
internal class QuotaAborted : RuntimeException("the quota was aborted", null, false, false)

/**
 * A run's share of requests at one service: at most [limit] at a time (ADR-019, decision 6). A call
 * waits for a share for as long as it is allowed to, and the wait ends at once when the thread is
 * interrupted or the quota is aborted. Together with the capacity of the resource, which limits the
 * runs that hold it, this is what keeps the service's concurrent requests at capacity times [limit]
 * at most.
 */
internal class RequestQuota(private val limit: Int) {
  private val lock = ReentrantLock()
  private val freed = lock.newCondition()
  private var used = 0
  private var aborted = false

  /**
   * Takes a share, waiting up to [timeoutMillis]; false when none came free in that time. Throws
   * [QuotaAborted] once the quota is aborted, and [InterruptedException] when the thread is.
   */
  fun acquire(timeoutMillis: Long): Boolean {
    lock.lockInterruptibly()
    try {
      var nanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
      while (true) {
        if (aborted) throw QuotaAborted()
        if (used < limit) {
          used++
          return true
        }
        if (nanos <= 0) return false
        nanos = freed.awaitNanos(nanos)
      }
    } finally {
      lock.unlock()
    }
  }

  fun release() = lock.withLock {
    used--
    freed.signal()
  }

  /** From now on no share is given, and every call that waits for one is woken. */
  fun abort() = lock.withLock {
    aborted = true
    freed.signalAll()
  }
}
