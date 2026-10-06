package dev.lawlan.runline.engine.trigger

import java.time.Instant
import java.util.UUID

/** Persistence of triggers and of the record of their firings. */
interface TriggerStore {
  /** Stores a trigger; null, and nothing changes, when one with that name exists. */
  fun insert(trigger: NewTrigger): Trigger?

  fun find(name: String): Trigger?

  /** Every trigger, by name. */
  fun list(): List<Trigger>

  /** Every enabled cron trigger. */
  fun enabledCron(): List<Trigger>

  /** Replaces the changeable state of the trigger; null when there is no such trigger. */
  fun update(name: String, change: TriggerChange, by: String, at: Instant): Trigger?

  /** Replaces the stored secret hash of a webhook trigger; null for no such webhook trigger. */
  fun replaceSecret(name: String, secretHash: String, by: String, at: Instant): Trigger?

  /** Deletes the trigger and its record of firings; false when there is no such trigger. */
  fun delete(name: String): Boolean

  /** What checking a call to the webhook trigger [name] needs; null for no such webhook. */
  fun webhookCredential(name: String): WebhookCredential?

  /**
   * Claims the delivery [deliveryId] of a trigger for a new firing and returns the firing, or null
   * when the delivery was claimed before. A delivery whose earlier attempt ended in an unexpected
   * failure can be claimed again; every other earlier attempt makes this one a duplicate. Racing
   * claims of the same delivery get exactly one winner.
   */
  fun claimDelivery(triggerId: Long, deliveryId: String, at: Instant): Long?

  /** Claims the scheduled time [scheduledFor] of a cron trigger; null when already claimed. */
  fun claimOccurrence(triggerId: Long, scheduledFor: Instant, at: Instant): Long?

  /** Records how a claimed firing ended. */
  fun settle(firingId: Long, outcome: FiringOutcome, reason: String?, detail: String?, run: UUID?)

  /** The most recent firings of a trigger, newest first. */
  fun firings(triggerId: Long, limit: Int): List<Firing>

  /** Marks firings left pending by an earlier process as interrupted; returns how many. */
  fun interruptPending(): Int
}
