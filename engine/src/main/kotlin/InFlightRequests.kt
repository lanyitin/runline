package dev.lawlan.runline.engine

import java.time.Duration
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Counts the requests being handled so that a shutdown can stop admitting new ones and wait for
 * those in flight.
 */
class InFlightRequests {
  private val lock = ReentrantLock()
  private val idle = lock.newCondition()
  private var inFlight = 0
  private var draining = false

  /**
   * Admits a request, unless draining has begun. Every request admitted as [counted] must [end];
   * one that is not counted (a session that stays open for as long as it likes) is not waited for.
   */
  fun tryBegin(counted: Boolean = true): Boolean = lock.withLock {
    if (draining) false else true.also { if (counted) inFlight++ }
  }

  fun end() {
    lock.withLock {
      inFlight--
      if (inFlight == 0) idle.signalAll()
    }
  }

  /**
   * Stops admitting requests and waits until none is in flight, at most [timeout]. Returns whether
   * everything in flight ended in time.
   */
  fun drain(timeout: Duration): Boolean = lock.withLock {
    draining = true
    var remaining = timeout.toNanos()
    while (inFlight > 0) {
      if (remaining <= 0) return false
      remaining = idle.awaitNanos(remaining)
    }
    true
  }
}
