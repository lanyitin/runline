package dev.lawlan.runline.engine

import dev.lawlan.runline.engine.auth.ApiAuthenticationProvider
import dev.lawlan.runline.engine.auth.ApiIdentity
import dev.lawlan.runline.engine.auth.CsrfGuard
import dev.lawlan.runline.engine.auth.SessionIdentities
import dev.lawlan.runline.engine.auth.TokenAuthenticator
import dev.lawlan.runline.engine.config.EngineConfig
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.di.*

/** Name of the Bearer authentication provider of the upload and management API (ADR-012). */
const val API_AUTHENTICATION = "api"

/**
 * The authentication extension point, with the API's Bearer provider registered. Which token is who
 * is decided by the [TokenAuthenticator] taken from dependency injection, so the way tokens are
 * provisioned can be replaced without touching routes. Routes opt in with `authorized`; endpoints
 * that do not (WebSocket, Swagger, the sample JSON route) stay open.
 */
fun Application.configureSecurity() {
  val authenticator: TokenAuthenticator by dependencies
  val config: EngineConfig by dependencies
  val oidc = config.oidc
  // Sessions exist only when login is configured; without it the API is the Bearer API it was.
  val identities: SessionIdentities? =
      if (oidc != null) {
        val found: SessionIdentities by dependencies
        found
      } else null
  install(Authentication) {
    register(
        ApiAuthenticationProvider(
            ApiAuthenticationProvider.Config(API_AUTHENTICATION).apply {
              realm = "runline"
              tokens = authenticator
              sessions = identities
              csrf = oidc?.let { CsrfGuard(it.publicBaseUrl) }
            }
        )
    )
  }
}

/** The authenticated caller of this request; only valid inside an `authorized` route. */
val ApplicationCall.caller: ApiIdentity
  get() = checkNotNull(principal<ApiIdentity>()) { "route is not behind authentication" }
