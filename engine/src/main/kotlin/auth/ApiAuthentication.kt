package dev.lawlan.runline.engine.auth

import dev.lawlan.runline.engine.artifact.ErrorResponse
import io.ktor.http.*
import io.ktor.http.auth.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.net.URI
import java.security.MessageDigest

/** Who a session cookie belongs to, and the CSRF token that goes with that session. */
class SessionIdentity(val identity: ApiIdentity, val csrfToken: String) {
  // The CSRF token belongs to the session and to nobody's log.
  override fun toString() = "SessionIdentity(${identity.name})"
}

/** Finds the session of a cookie; null when there is none that is valid. */
fun interface SessionIdentities {
  fun identify(sessionId: String?): SessionIdentity?
}

/**
 * The CSRF defence of a request that is authenticated by a session (ADR-029, 05): a change must
 * come with the CSRF token of the session in `X-CSRF-Token`, and an `Origin` that is the Engine's
 * own. A page of another site is sent the cookie with the browser's own request but cannot read the
 * token and cannot send the Engine's origin. Reads are not guarded (they change nothing).
 */
class CsrfGuard(publicBaseUrl: String) {
  /** Why a change by a session is refused; the code is the stable one of the API. */
  enum class Refusal(val code: String, val message: String) {
    ORIGIN("origin_invalid", "請求的 Origin 不是本站，已拒絕。"),
    TOKEN("csrf_invalid", "缺少或不符的 CSRF token，已拒絕。"),
  }

  private val own = originOf(publicBaseUrl)

  fun check(method: HttpMethod, origin: String?, presented: String?, expected: String): Refusal? {
    if (method in READS) return null
    if (origin == null || originOf(origin) != own) return Refusal.ORIGIN
    val same =
        presented != null &&
            MessageDigest.isEqual(
                presented.toByteArray(Charsets.UTF_8),
                expected.toByteArray(Charsets.UTF_8),
            )
    return if (same) null else Refusal.TOKEN
  }

  /** Scheme, host and port, compared in their normal form; null for anything that is no origin. */
  private fun originOf(value: String): Triple<String, String, Int>? =
      try {
        val uri = URI(value)
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        val port = if (uri.port == -1) DEFAULT_PORTS[scheme] else uri.port
        if (scheme == null || host == null || port == null || !uri.rawPath.isNullOrEmpty()) null
        else Triple(scheme, host, port)
      } catch (e: java.net.URISyntaxException) {
        null
      }

  private companion object {
    val READS = setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Options)
    val DEFAULT_PORTS = mapOf("http" to 80, "https" to 443)
  }
}

/**
 * The one authentication of the API (ADR-012, ADR-029). A request with an `Authorization` header is
 * a Bearer request and nothing else: a token that is not valid is a 401, and a cookie is not looked
 * at. Without the header, the session cookie is, when sessions exist; a change by a session must
 * pass the [CsrfGuard] (403 otherwise, before anything is done). Either way the result is an
 * [ApiIdentity] of a name and a role, so what a caller may do is decided by one set of rules.
 */
class ApiAuthenticationProvider(private val config: Config) : AuthenticationProvider(config) {
  class Config(name: String) : AuthenticationProvider.Config(name) {
    var realm: String = "runline"
    lateinit var tokens: TokenAuthenticator
    var sessions: SessionIdentities? = null
    var csrf: CsrfGuard? = null
  }

  override suspend fun onAuthenticate(context: AuthenticationContext) {
    val call = context.call
    if (call.request.headers[HttpHeaders.Authorization] != null) {
      val header = runCatching { call.request.parseAuthorizationHeader() }.getOrNull()
      val token =
          (header as? HttpAuthHeader.Single)?.takeIf { it.authScheme.equals("Bearer", true) }?.blob
      val identity = token?.let(config.tokens::authenticate)
      if (identity != null) context.principal(identity) else unauthorized(context)
      return
    }
    val sessions = config.sessions
    val sessionId = call.request.cookies[SESSION_COOKIE_NAME]
    val session = if (sessions != null) sessions.identify(sessionId) else null
    if (session == null) return unauthorized(context)

    val refusal =
        config.csrf?.check(
            call.request.httpMethod,
            call.request.headers[HttpHeaders.Origin],
            call.request.headers[CSRF_HEADER],
            session.csrfToken,
        )
    if (refusal == null) {
      context.principal(session.identity)
    } else {
      context.challenge("csrf", AuthenticationFailedCause.Error(refusal.code)) { challenge, c ->
        c.respond(HttpStatusCode.Forbidden, ErrorResponse(refusal.code, refusal.message))
        challenge.complete()
      }
    }
  }

  private fun unauthorized(context: AuthenticationContext) {
    context.challenge("bearer", AuthenticationFailedCause.InvalidCredentials) { challenge, call ->
      call.respond(
          UnauthorizedResponse(
              HttpAuthHeader.Parameterized(
                  "Bearer",
                  mapOf("realm" to config.realm),
                  HeaderValueEncoding.QUOTED_ALWAYS,
              )
          )
      )
      challenge.complete()
    }
  }

  companion object {
    /** The name of the cookie of a session; the sign-in routes set it. */
    const val SESSION_COOKIE_NAME = "runline_session"
    const val CSRF_HEADER = "X-CSRF-Token"
  }
}

private const val SESSION_COOKIE_NAME = ApiAuthenticationProvider.SESSION_COOKIE_NAME
private const val CSRF_HEADER = ApiAuthenticationProvider.CSRF_HEADER

/** The routes under [build] answer to Bearer tokens only, whatever else would be accepted. */
fun Route.bearerOnly() {
  intercept(ApplicationCallPipeline.Call) {
    if (call.principal<ApiIdentity>()?.source != AuthSource.TOKEN) {
      call.response.header(HttpHeaders.WWWAuthenticate, "Bearer realm=\"runline\"")
      call.respond(HttpStatusCode.Unauthorized)
      finish()
    }
  }
}
