package dev.lawlan.runline.engine

import dev.lawlan.runline.engine.auth.ApiIdentity
import dev.lawlan.runline.engine.auth.TokenAuthenticator
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
  install(Authentication) {
    bearer(API_AUTHENTICATION) {
      realm = "runline"
      authenticate { credential -> authenticator.authenticate(credential.token) }
    }
  }
}

/** The authenticated caller of this request; only valid inside an `authorized` route. */
val ApplicationCall.caller: ApiIdentity
  get() = checkNotNull(principal<ApiIdentity>()) { "route is not behind authentication" }
