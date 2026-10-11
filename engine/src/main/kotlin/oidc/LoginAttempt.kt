package dev.lawlan.runline.engine.oidc

import java.time.Instant

/** What the Engine remembers of a sign-in between sending the person away and their return. */
data class LoginAttempt(
    val state: String,
    val nonce: String,
    val codeVerifier: String,
    val returnPath: String,
    val createdAt: Instant,
) {
  // The verifier is a secret until the code is redeemed; it must not reach a log line by accident.
  override fun toString() = "LoginAttempt(createdAt=$createdAt)"
}

/** How long a person has to come back from the provider (minutes, not hours). */
val LOGIN_ATTEMPT_TTL: java.time.Duration = java.time.Duration.ofMinutes(10)

sealed interface SaveResult {
  data object Saved : SaveResult

  /** There is no room; [oldest] is when the oldest attempt that counts was made. */
  data class Full(val oldest: Instant) : SaveResult
}

/** Keeps the sign-ins that are under way. */
interface LoginAttemptStore {
  /**
   * Keeps [attempt] unless [limit] attempts made at or after [notBefore] (the ones still good) are
   * already kept; then nothing is written. The count and the write are one step, so any number of
   * callers at the same moment keep at most [limit] between them. Attempts that are too old do not
   * count, so the room comes back as they expire, with or without the clean-up.
   */
  fun save(attempt: LoginAttempt, limit: Int, notBefore: Instant): SaveResult

  /**
   * Takes the attempt of [state] away and returns it, if it is there and was made at or after
   * [notBefore]; an attempt that is too old is removed and is not returned. Of any number of calls,
   * also at the same moment, at most one returns the attempt.
   */
  fun consume(state: String, notBefore: Instant): LoginAttempt?
}
