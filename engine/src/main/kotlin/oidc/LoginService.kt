package dev.lawlan.runline.engine.oidc

import dev.lawlan.runline.engine.config.OidcSettings
import io.opentelemetry.api.trace.Span
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import org.slf4j.LoggerFactory

/**
 * The stable codes of a sign-in that did not happen (05-ipc); the Console shows a text for each.
 */
enum class LoginError(val code: String) {
  ACCESS_DENIED("access_denied"),
  INVALID_STATE("invalid_state"),
  INVALID_TOKEN("invalid_token"),
  NOT_AUTHORIZED("not_authorized"),
  NAME_CONFLICT("name_conflict"),
  REFRESH_UNAVAILABLE("refresh_unavailable"),
  USER_DISABLED("user_disabled"),
  PROVIDER_UNAVAILABLE("provider_unavailable"),
  TOO_MANY_LOGINS("too_many_logins"),
}

/** The room for sign-ins under way is used up; try again in [retryAfter] (ADR-030). */
class LoginCapacityException(val retryAfter: java.time.Duration) :
    RuntimeException("too many sign-ins under way")

/** What the provider sent the browser back with. */
class LoginCallback(val code: String?, val state: String?, val error: String?) {
  // The code is a credential until it is redeemed.
  override fun toString() = "LoginCallback()"
}

sealed interface LoginOutcome {
  /** The identifier goes into the cookie, once; no text form shows it. */
  class SignedIn(val sessionId: String, val returnPath: String, val expiresAt: Instant) :
      LoginOutcome {
    override fun toString() = "SignedIn(returnPath=$returnPath)"
  }

  data class Refused(val error: LoginError) : LoginOutcome
}

/**
 * Signing in with the provider (ADR-029, 05-ipc), in two steps. [start] makes a sign-in and says
 * where to send the person; [complete] takes the person's return and, if all is well, makes the
 * session. The state of a sign-in is in the database and is used once; the code is redeemed once
 * and never again; the person is told by a stable code why they were refused, and the log, the
 * metrics and the trace say the same with a name and a role at most.
 */
class LoginService(
    private val provider: OidcProvider,
    private val attempts: LoginAttemptStore,
    private val sessions: SessionStore,
    private val cipher: SessionCipher,
    private val settings: OidcSettings,
    /** Names a new person may not have: those of the API tokens. */
    private val reservedNames: Set<String>,
    private val clock: Clock,
    private val telemetry: AuthTelemetry,
) {
  private val log = LoggerFactory.getLogger(LoginService::class.java)
  private val groupRoles = GroupRoles(settings.adminGroup, settings.developerGroup)

  /**
   * Where to send the person to sign in. The sign-in is kept only when the provider could be asked.
   *
   * @throws OidcFailure when the provider cannot be used
   * @throws LoginCapacityException when too many sign-ins are under way
   */
  fun start(returnPath: String?): URI {
    val state = SessionIds.newToken()
    val nonce = SessionIds.newToken()
    val verifier = SessionIds.newToken()
    val uri = provider.authorizationUri(state, nonce, verifier)
    val now = clock.instant()
    val saved =
        attempts.save(
            LoginAttempt(state, nonce, verifier, ReturnPath.sanitize(returnPath), now),
            settings.maxPendingLogins,
            now - LOGIN_ATTEMPT_TTL,
        )
    if (saved is SaveResult.Full) {
      // The room is used up until the oldest sign-in under way expires (ADR-030).
      val wait = Duration.between(now, saved.oldest + LOGIN_ATTEMPT_TTL)
      telemetry.refused("too_many_logins")
      log.warn("Sign-in not started: too many are under way")
      throw LoginCapacityException(wait.coerceAtLeast(Duration.ofSeconds(1)))
    }
    return uri
  }

  fun complete(callback: LoginCallback): LoginOutcome =
      telemetry.traced("auth.login.complete") { span ->
        val outcome = attempt(callback, span)
        when (outcome) {
          is LoginOutcome.SignedIn -> span.setAttribute(AuthTelemetry.RESULT, "signed_in")
          is LoginOutcome.Refused -> span.setAttribute(AuthTelemetry.RESULT, outcome.error.code)
        }
        outcome
      }

  private fun attempt(callback: LoginCallback, span: Span): LoginOutcome {
    val now = clock.instant()
    val attempt =
        callback.state?.let { attempts.consume(it, now - LOGIN_ATTEMPT_TTL) }
            ?: return refuse(LoginError.INVALID_STATE)
    if (callback.error != null) return refuse(LoginError.ACCESS_DENIED)
    val code = callback.code?.takeIf { it.isNotEmpty() } ?: return refuse(LoginError.INVALID_TOKEN)

    val login =
        try {
          provider.redeem(code, attempt.codeVerifier, attempt.nonce)
        } catch (e: OidcFailure) {
          return refuse(
              when (e.kind) {
                OidcFailure.Kind.UNAVAILABLE -> LoginError.PROVIDER_UNAVAILABLE
                OidcFailure.Kind.REJECTED,
                OidcFailure.Kind.ID_TOKEN_INVALID,
                OidcFailure.Kind.UNACCEPTED_ALGORITHM -> LoginError.INVALID_TOKEN
              },
              hint =
                  if (e.kind == OidcFailure.Kind.UNACCEPTED_ALGORITHM) {
                    "the ID token is signed with ${e.algorithm}, which is not accepted; only " +
                        "RS256 is, so the provider needs a signing key chosen for this client"
                  } else null,
          )
        }
    val username = login.username?.takeIf { it.isNotBlank() }
    val role = groupRoles.roleOf(login.groups) ?: return refuse(LoginError.NOT_AUTHORIZED, username)
    if (username == null) return refuse(LoginError.INVALID_TOKEN)
    // Fail closed (ADR-030): a session whose access cannot be taken back is not made. Nothing has
    // been written yet, so a person who is known is left as they were.
    val refreshToken =
        login.refreshToken?.takeIf { it.isNotEmpty() }
            ?: return refuse(
                LoginError.REFRESH_UNAVAILABLE,
                username,
                "the provider returned no refresh token; check that the client may use the " +
                    "offline_access scope and that the provider issues refresh tokens for it",
            )

    val id = SessionIds.newToken()
    val hash = SessionIds.hash(id)
    val expiresAt = now + settings.session.absolute
    val result =
        sessions.signIn(
            SignInIdentity(settings.issuer, login.subject, username, login.displayName ?: username),
            NewSession(
                hash,
                role,
                SessionIds.newToken(),
                cipher.seal(refreshToken, hash),
                now,
                expiresAt,
            ),
            now,
            reservedNames,
        )
    return when (result) {
      is SignInResult.Disabled -> refuse(LoginError.USER_DISABLED, username)
      is SignInResult.NameTaken -> {
        telemetry.nameConflict()
        refuse(LoginError.NAME_CONFLICT, username)
      }
      is SignInResult.SignedIn -> {
        span.setAttribute(AuthTelemetry.ROLE, role.name)
        telemetry.signedIn(role.name)
        log.info("Signed in {} as {}", result.user.name, role)
        LoginOutcome.SignedIn(id, attempt.returnPath, expiresAt)
      }
    }
  }

  private fun refuse(error: LoginError, name: String? = null, hint: String? = null): LoginOutcome {
    telemetry.refused(error.code)
    val who = if (name != null) " ($name)" else ""
    val why = if (hint != null) ": $hint" else ""
    log.warn("Sign-in refused: {}{}{}", error.code, who, why)
    return LoginOutcome.Refused(error)
  }
}
