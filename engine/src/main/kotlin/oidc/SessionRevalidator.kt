package dev.lawlan.runline.engine.oidc

import dev.lawlan.runline.engine.config.OidcSettings
import java.time.Clock
import java.time.Duration
import java.time.Instant
import org.slf4j.LoggerFactory

/**
 * Checks a session in use with the provider again (WI-1205, ADR-029 decision 9), so that what is
 * taken back at the provider is taken back here within an interval:
 *
 * - the refresh token of the session asks for a new ID token; the groups in it give the role, which
 *   replaces the role of the session (up or down), or none, and the session is removed;
 * - a provider that refuses (the person is disabled, the session revoked, the token not good) ends
 *   the session; one that cannot be reached lets it live for the grace time from the last success,
 *   or, with no grace, not at all;
 * - a refresh token the provider rotated is kept, and a refresh token is sent once, whatever comes
 *   of it.
 *
 * What is learned is written to the database, not kept here, and the requests of one session take
 * turns on a lock the database holds, so the provider is asked once for a session whatever the
 * number of requests or processes. A session that was checked, or tried, less than an interval ago
 * is not checked. Nothing said or recorded here holds a token or an identifier.
 */
class SessionRevalidator(
    private val store: SessionStore,
    private val provider: OidcProvider,
    private val cipher: SessionCipher,
    private val settings: OidcSettings,
    private val clock: Clock,
    private val telemetry: AuthTelemetry,
) {
  private val log = LoggerFactory.getLogger(SessionRevalidator::class.java)
  private val groupRoles = GroupRoles(settings.adminGroup, settings.developerGroup)
  private val interval = settings.session.revalidate
  private val grace = settings.session.revalidateGrace

  /** Whether the session has gone an interval without being confirmed or tried. */
  fun isDue(session: StoredSession, now: Instant): Boolean {
    val last = listOfNotNull(session.lastValidatedAt, session.lastAttemptAt).max()
    return !now.isBefore(last + interval)
  }

  /**
   * The session after it was checked, or null when it is gone: ended by the check, or by anything
   * else in the meantime.
   */
  fun revalidate(idHash: ByteArray): StoredSession? {
    val now = clock.instant()
    return store.exclusively(idHash, now, settings.session.idle) { session ->
      when {
        session == null -> null
        // Another request of this session got the answer while this one waited.
        !isDue(session, now) -> session
        else -> check(idHash, session, now)
      }
    }
  }

  private fun check(idHash: ByteArray, session: StoredSession, now: Instant): StoredSession? {
    val name = session.user.name
    val token =
        session.refreshToken
            .takeIf { it.isNotEmpty() }
            ?.let { cipher.open(it, idHash) }
            ?.takeIf { it.isNotEmpty() } ?: return revoke(idHash, name, "no_refresh_token")
    val refreshed =
        try {
          provider.refresh(token)
        } catch (e: OidcFailure) {
          return when (e.kind) {
            OidcFailure.Kind.UNAVAILABLE -> unavailable(idHash, session, now)
            OidcFailure.Kind.REJECTED -> revoke(idHash, name, "rejected")
            OidcFailure.Kind.ID_TOKEN_INVALID -> revoke(idHash, name, "id_token_invalid")
            OidcFailure.Kind.UNACCEPTED_ALGORITHM -> revoke(idHash, name, "id_token_invalid")
          }
        }
    val role = groupRoles.roleOf(refreshed.groups) ?: return revoke(idHash, name, "not_authorized")
    store.recordValidation(
        idHash,
        role,
        refreshed.refreshToken?.takeIf { it != token }?.let { cipher.seal(it, idHash) },
        now,
    )
    if (role != session.role) {
      telemetry.revalidated("role_changed")
      log.info("Role of {} changed from {} to {} at the provider", name, session.role, role)
    } else {
      telemetry.revalidated("renewed")
      log.debug("Session of {} confirmed by the provider", name)
    }
    return session.copy(role = role, lastValidatedAt = now, lastAttemptAt = null)
  }

  private fun unavailable(idHash: ByteArray, session: StoredSession, now: Instant): StoredSession? {
    val name = session.user.name
    val allowedUntil = session.lastValidatedAt + grace
    if (grace > Duration.ZERO && !now.isAfter(allowedUntil)) {
      store.recordAttempt(idHash, now)
      telemetry.revalidated("grace")
      log.warn(
          "The provider could not be reached to check the session of {}; it stays valid until {}",
          name,
          allowedUntil,
      )
      return session.copy(lastAttemptAt = now)
    }
    store.end(idHash)
    telemetry.revalidated("grace_expired")
    log.warn(
        "The provider could not be reached and the grace time of the session of {} is over; " +
            "the session was ended",
        name,
    )
    return null
  }

  private fun revoke(idHash: ByteArray, name: String, reason: String): StoredSession? {
    store.end(idHash)
    telemetry.revalidated("revoked", reason)
    log.info("Session of {} ended by the check with the provider: {}", name, reason)
    return null
  }
}
