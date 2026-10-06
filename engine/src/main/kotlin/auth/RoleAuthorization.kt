package dev.lawlan.runline.engine.auth

import dev.lawlan.runline.engine.API_AUTHENTICATION
import dev.lawlan.runline.engine.artifact.ErrorResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

class RoleAuthorizationConfig {
  var required: Role = Role.DEVELOPER
}

private val RoleAuthorization =
    createRouteScopedPlugin("RoleAuthorization", ::RoleAuthorizationConfig) {
      on(AuthenticationChecked) { call ->
        val identity = call.principal<ApiIdentity>()
        if (identity != null && !identity.role.permits(pluginConfig.required)) {
          call.respond(
              HttpStatusCode.Forbidden,
              ErrorResponse("forbidden", "此操作需要 ${pluginConfig.required} 角色。"),
          )
        }
      }
    }

private class AuthorizedRouteSelector(private val role: Role) : RouteSelector() {
  override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int) =
      RouteSelectorEvaluation.Constant

  override fun toString() = "(authorized ${role})"
}

/**
 * Routes under [build] require a valid Bearer token whose role satisfies [role]. A missing or
 * invalid token is answered 401 by the authentication provider; a valid token with too little
 * authority is answered 403 here.
 */
fun Route.authorized(role: Role, build: Route.() -> Unit): Route =
    authenticate(API_AUTHENTICATION) {
      createChild(AuthorizedRouteSelector(role)).also {
        it.install(RoleAuthorization) { required = role }
        it.build()
      }
    }
