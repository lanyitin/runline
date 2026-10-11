package dev.lawlan.runline.engine.oidc

import dev.lawlan.runline.engine.auth.Role
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Who the provider says is signing in, and the name they will have here if it is their first time.
 */
data class SignInIdentity(
    val issuer: String,
    val subject: String,
    val name: String,
    val displayName: String,
)

/** A person who has signed in. The [name] is the one ownership is kept under, and never changes. */
data class SignedInUser(val id: UUID, val name: String, val displayName: String)

/** A session to store. Only the hash of its identifier is ever given to the store. */
class NewSession(
    val idHash: ByteArray,
    val role: Role,
    val csrfToken: String,
    /** The refresh token, already sealed. A session without one is not made (ADR-030). */
    val refreshToken: ByteArray,
    val createdAt: Instant,
    val expiresAt: Instant,
)

/** A session found valid, and whose person it is. */
class StoredSession(
    val user: SignedInUser,
    val role: Role,
    val csrfToken: String,
    val refreshToken: ByteArray,
    val createdAt: Instant,
    val lastUsedAt: Instant,
    val lastValidatedAt: Instant,
    val expiresAt: Instant,
    /** When the provider was last asked and could not be reached; null if never. */
    val lastAttemptAt: Instant? = null,
) {
  fun copy(
      lastUsedAt: Instant = this.lastUsedAt,
      role: Role = this.role,
      lastValidatedAt: Instant = this.lastValidatedAt,
      lastAttemptAt: Instant? = this.lastAttemptAt,
  ) =
      StoredSession(
          user,
          role,
          csrfToken,
          refreshToken,
          createdAt,
          lastUsedAt,
          lastValidatedAt,
          expiresAt,
          lastAttemptAt,
      )
}

sealed interface SignInResult {
  data class SignedIn(val user: SignedInUser) : SignInResult

  /** Another person already has the name; nothing was stored. */
  data object NameTaken : SignInResult

  /** An administrator disabled the person here; nothing was stored (WI-1205). */
  data object Disabled : SignInResult
}

/** Keeps the people who signed in and their sessions; all state is in the database. */
interface SessionStore {
  /**
   * In one transaction: makes the person of [identity] if the provider's subject is new (with the
   * name of the identity), or finds them (the name they have stays, their display name and last
   * sign-in are brought up to date), and stores [session] for them. When the name is taken by
   * another person, or is one of [reservedNames] (the names of API tokens), nothing is stored at
   * all; a person who is known keeps their name whatever is reserved now. Any number of sign-ins of
   * one new person at the same time make one person.
   */
  fun signIn(
      identity: SignInIdentity,
      session: NewSession,
      now: Instant,
      reservedNames: Set<String> = emptySet(),
  ): SignInResult

  /**
   * The session with [idHash], if it is valid at [now]: before its expiry, and used within [idle]
   * of now. Finding it counts as using it, but the time of the use is only written when the last
   * one written is [touchAfter] old or older, so a burst of requests is one write; a session is
   * then judged idle at most [touchAfter] earlier than the last request. Nothing else finds a
   * session; one past either limit is as good as gone, whether or not it was removed yet.
   */
  fun resume(
      idHash: ByteArray,
      now: Instant,
      idle: Duration,
      touchAfter: Duration = Duration.ZERO,
  ): StoredSession?

  /**
   * Runs [action] holding the lock of the session, with the session as it is once the lock is had
   * (null if it is gone or not valid at [now]). Callers for the same session, in this process or
   * any other, take turns, so what one learns from the provider is known to the next without asking
   * again.
   */
  fun <T> exclusively(
      idHash: ByteArray,
      now: Instant,
      idle: Duration,
      action: (StoredSession?) -> T,
  ): T

  /** The provider confirmed the session at [now]: its [role], and the new sealed refresh token. */
  fun recordValidation(idHash: ByteArray, role: Role, refreshToken: ByteArray?, now: Instant)

  /** The provider was asked at [now] and could not be reached. */
  fun recordAttempt(idHash: ByteArray, now: Instant)

  /** Removes the session; true if there was one. Removing what is not there does nothing. */
  fun end(idHash: ByteArray): Boolean
}
