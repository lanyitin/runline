package dev.lawlan.runline.engine.retention

import java.time.Instant

/**
 * Removes expired run, log and trigger firing records (WI-20). Every call removes at most [limit]
 * rows and returns how many it removed, so a backlog is cleared by calling until fewer than [limit]
 * come back; calling again when nothing is expired removes nothing.
 */
interface RetentionStore {
  /**
   * Removes log entries of runs that ended before [endedBefore]. Runs that have not ended are never
   * touched; the runs themselves stay.
   */
  fun deleteExpiredLogEntries(endedBefore: Instant, limit: Int): Int

  /**
   * Removes runs that ended before [endedBefore] and have no log left. A firing that created the
   * run is kept without it. Runs that have not ended are never removed.
   */
  fun deleteExpiredRuns(endedBefore: Instant, limit: Int): Int

  /** Removes webhook firings from before [firedBefore], except those still pending. */
  fun deleteExpiredWebhookFirings(firedBefore: Instant, limit: Int): Int

  /** Removes cron firings from before [firedBefore], except those still pending. */
  fun deleteExpiredCronFirings(firedBefore: Instant, limit: Int): Int
}
