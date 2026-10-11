package dev.lawlan.runline.engine.oidc

import java.time.Duration
import java.time.Instant

/** A person who has signed in, as an administrator sees them. */
data class UserRecord(
    val name: String,
    val displayName: String,
    val disabled: Boolean,
    /** Who last disabled or enabled the person, and when; null if nobody ever did. */
    val statusChangedBy: String?,
    val statusChangedAt: Instant?,
    val createdAt: Instant,
    val lastLoginAt: Instant,
    /** Sessions that are valid now. */
    val sessions: Int,
)

/** A session as an administrator sees it: a handle to name it by, never the identifier. */
data class SessionInfo(val ref: String, val createdAt: Instant, val lastUsedAt: Instant)

sealed interface DisableResult {
  data object NotFound : DisableResult

  /** [sessionsEnded] is how many sessions were removed by it (none when enabling). */
  data class Changed(val user: UserRecord, val sessionsEnded: Int) : DisableResult
}

/** What administrators do with the people who signed in and their sessions (WI-1205). */
interface UserAdminStore {
  fun list(now: Instant, idle: Duration): List<UserRecord>

  fun find(name: String, now: Instant, idle: Duration): UserRecord?

  /**
   * Disables or enables the person called [name], recording [by] and [now]. Disabling removes every
   * session of the person in the same transaction, and a sign-in or a check of a session that runs
   * at the same moment cannot leave one behind.
   */
  fun setDisabled(
      name: String,
      disabled: Boolean,
      by: String,
      now: Instant,
      idle: Duration,
  ): DisableResult

  /** The valid sessions of the person; null when there is no such person. */
  fun sessions(name: String, now: Instant, idle: Duration): List<SessionInfo>?

  /** Ends the session [ref] of the person; true if there was one. */
  fun endSession(name: String, ref: String): Boolean

  /** Ends every session of the person; the number ended, or null when there is no such person. */
  fun endAll(name: String): Int?
}
