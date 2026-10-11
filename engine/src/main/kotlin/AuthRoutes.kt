package dev.lawlan.runline.engine

import dev.lawlan.runline.engine.artifact.ErrorResponse
import dev.lawlan.runline.engine.auth.ApiAuthenticationProvider
import dev.lawlan.runline.engine.auth.CsrfGuard
import dev.lawlan.runline.engine.config.EngineConfig
import dev.lawlan.runline.engine.config.OidcSettings
import dev.lawlan.runline.engine.oidc.LoginCallback
import dev.lawlan.runline.engine.oidc.LoginCapacityException
import dev.lawlan.runline.engine.oidc.LoginError
import dev.lawlan.runline.engine.oidc.LoginOutcome
import dev.lawlan.runline.engine.oidc.LoginService
import dev.lawlan.runline.engine.oidc.OidcFailure
import dev.lawlan.runline.engine.oidc.SessionService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/** The name of the cookie of the session of a person who signed in (ADR-029). */
const val SESSION_COOKIE = "runline_session"

@Serializable data class OidcMethod(val enabled: Boolean)

@Serializable data class AuthMethodsResponse(val oidc: OidcMethod)

/** Who the session belongs to. [role] is `developer` or `admin`. */
@Serializable
data class SessionResponse(
    val name: String,
    val role: String,
    val displayName: String,
    val csrfToken: String,
)

private val log = LoggerFactory.getLogger("dev.lawlan.runline.engine.AuthRoutes")

/**
 * Signing in with an OpenID Connect provider (WI-1200, ADR-029, 05-ipc). Only the question which
 * ways of signing in there are is always answered; the rest exists only when login is configured,
 * and is a 404, like any other path under `/api` that is not there, when it is not.
 *
 * - `GET /api/v1/auth/methods`: open; whether OIDC login is on, for the Console's sign-in page.
 * - `GET /api/v1/auth/oidc/login`: open; sends the browser to the provider. When it cannot (too
 *   many sign-ins under way, or the provider cannot be reached) it sends the browser to the Console
 *   with a stable code, as the callback does (ADR-031); nothing is stored then.
 * - `GET /api/v1/auth/oidc/callback`: open, proven by the state; the provider sends the browser
 *   back here. Success sets the cookie and goes on to the way back; any failure goes to the Console
 *   with a stable code. Never cached, and the address with the code is not passed on.
 * - `GET /api/v1/auth/session`: the session cookie; who the session belongs to, or 401.
 * - `POST /api/v1/auth/logout`: ends the session and clears the cookie; the same when there is
 *   none.
 */
fun Application.configureAuthRoutes() {
  val config: EngineConfig by dependencies
  val oidc = config.oidc
  val dependencies = dependencies

  routing {
    get("/api/v1/auth/methods") {
      call.response.header(HttpHeaders.CacheControl, "no-store")
      call.respond(AuthMethodsResponse(OidcMethod(oidc != null)))
    }
    if (oidc != null) {
      val login: LoginService by dependencies
      val sessions: SessionService by dependencies
      val clock: Clock by dependencies
      val csrf = CsrfGuard(oidc.publicBaseUrl)

      get("/api/v1/auth/oidc/login") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        try {
          val provider =
              withContext(Dispatchers.IO) { login.start(call.request.queryParameters["return"]) }
          call.respondRedirect(provider.toString())
        } catch (e: LoginCapacityException) {
          call.respondRedirect("/?login_error=${LoginError.TOO_MANY_LOGINS.code}")
        } catch (e: OidcFailure) {
          log.warn("The provider could not be used to start a sign-in: {}", e.kind)
          call.respondRedirect("/?login_error=${LoginError.PROVIDER_UNAVAILABLE.code}")
        }
      }

      get("/api/v1/auth/oidc/callback") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.response.header("Referrer-Policy", "no-referrer")
        val query = call.request.queryParameters
        val outcome =
            withContext(Dispatchers.IO) {
              login.complete(LoginCallback(query["code"], query["state"], query["error"]))
            }
        when (outcome) {
          is LoginOutcome.SignedIn -> {
            val lifetime = Duration.between(clock.instant(), outcome.expiresAt)
            call.response.cookies.append(sessionCookie(oidc, outcome.sessionId, lifetime))
            call.respondRedirect(outcome.returnPath)
          }
          is LoginOutcome.Refused -> call.respondRedirect("/?login_error=${outcome.error.code}")
        }
      }

      get("/api/v1/auth/session") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        val session =
            withContext(Dispatchers.IO) { sessions.find(call.request.cookies[SESSION_COOKIE]) }
        if (session == null) {
          call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized", "尚未登入或登入已失效。"))
        } else {
          call.respond(
              SessionResponse(
                  session.name,
                  session.role.name.lowercase(),
                  session.displayName,
                  session.csrfToken,
              )
          )
        }
      }

      post("/api/v1/auth/logout") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        val id = call.request.cookies[SESSION_COOKIE]
        // Signing out is a change by a session, so it is guarded like one; with no session there is
        // nothing to protect, and nothing to refuse.
        val session = withContext(Dispatchers.IO) { sessions.find(id) }
        val refusal = session?.let {
          csrf.check(
              call.request.httpMethod,
              call.request.headers[HttpHeaders.Origin],
              call.request.headers[ApiAuthenticationProvider.CSRF_HEADER],
              it.csrfToken,
          )
        }
        if (refusal != null) {
          return@post call.respond(
              HttpStatusCode.Forbidden,
              ErrorResponse(refusal.code, refusal.message),
          )
        }
        withContext(Dispatchers.IO) { sessions.end(id) }
        call.response.cookies.append(sessionCookie(oidc, "", Duration.ZERO))
        call.respond(HttpStatusCode.NoContent)
      }
    }
  }
}

/**
 * The cookie of a session: out of reach of scripts, not sent along with requests from other sites
 * except when a person follows a link here, and over https only when the public address is https.
 */
private fun sessionCookie(oidc: OidcSettings, value: String, lifetime: Duration) =
    Cookie(
        name = SESSION_COOKIE,
        value = value,
        maxAge = lifetime.seconds.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
        path = "/",
        secure = oidc.secureCookie,
        httpOnly = true,
        extensions = mapOf("SameSite" to "Lax"),
    )
