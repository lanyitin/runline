package dev.lawlan.runline.engine.oidc

import dev.lawlan.runline.engine.auth.Role
import dev.lawlan.runline.engine.config.SessionSettings
import java.time.Clock
import java.time.Duration
import org.slf4j.LoggerFactory

/** What a valid session says about the person who holds it. */
data class SessionView(
    val name: String,
    val role: Role,
    val displayName: String,
    val csrfToken: String,
) {
  // The CSRF token belongs to the session; it is given to its holder and nowhere else.
  override fun toString() = "SessionView(name=$name, role=$role)"
}

/**
 * The sessions of people who signed in, found by the identifier in their cookie (ADR-029). Whether
 * a session is valid is decided by the database against the clock and the settings on every
 * question, so a session that ran out is as good as gone before any clean-up. Nothing is kept in
 * the memory of the process.
 */
class SessionService(
    private val store: SessionStore,
    private val settings: SessionSettings,
    private val clock: Clock,
    private val telemetry: AuthTelemetry,
    private val revalidator: SessionRevalidator,
) {
  private val log = LoggerFactory.getLogger(SessionService::class.java)

  // The time of use is written at most once in this time (a minute, or a quarter of the idle time
  // when that is shorter), so the requests of a page are not a write each (WI-1201).
  private val touchInterval = minOf(Duration.ofMinutes(1), settings.idle.dividedBy(4))

  /** The session with [sessionId], counted as used; null when there is none, or it is not valid. */
  fun find(sessionId: String?): SessionView? {
    if (sessionId.isNullOrEmpty()) return null
    val hash = SessionIds.hash(sessionId)
    val now = clock.instant()
    val found = store.resume(hash, now, settings.idle, touchInterval) ?: return null
    // A session that has gone an interval is checked with the provider first (WI-1205); what it
    // says may end the session or change its role, and the request is answered by the result.
    val session = if (revalidator.isDue(found, now)) revalidator.revalidate(hash) else found
    return session?.let { SessionView(it.user.name, it.role, it.user.displayName, it.csrfToken) }
  }

  /** Ends the session with [sessionId]; nothing happens when there is none. */
  fun end(sessionId: String?) {
    telemetry.traced("auth.logout") { span ->
      val ended = sessionId.isNullOrEmpty().not() && endExisting(sessionId!!)
      span.setAttribute(AuthTelemetry.RESULT, if (ended) "signed_out" else "no_session")
    }
  }

  private fun endExisting(sessionId: String): Boolean {
    val hash = SessionIds.hash(sessionId)
    val session = store.resume(hash, clock.instant(), settings.idle, touchInterval)
    if (!store.end(hash)) return false
    telemetry.signedOut()
    // A session that had run out has no name here; it ended by itself before.
    if (session != null) log.info("Signed out {}", session.user.name)
    return true
  }
}
