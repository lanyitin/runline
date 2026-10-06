package dev.lawlan.runline.engine.support

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A clock that only moves when a test says so, for durations that must be exact. */
class MutableClock(@Volatile private var now: Instant = Instant.parse("2026-10-04T10:00:00Z")) :
    Clock() {
  override fun getZone(): ZoneId = ZoneOffset.UTC

  override fun withZone(zone: ZoneId): Clock = this

  override fun instant(): Instant = now

  fun advance(by: Duration) {
    now = now.plus(by)
  }
}
