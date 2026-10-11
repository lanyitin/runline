package dev.lawlan.runline.engine

import dev.lawlan.runline.engine.artifact.ErrorResponse
import dev.lawlan.runline.engine.auth.Role
import dev.lawlan.runline.engine.auth.authorized
import dev.lawlan.runline.engine.config.EngineConfig
import dev.lawlan.runline.engine.oidc.SessionInfo
import dev.lawlan.runline.engine.oidc.UserAdmin
import dev.lawlan.runline.engine.oidc.UserChange
import dev.lawlan.runline.engine.oidc.UserRecord
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** A person who signed in, as an administrator sees them (WI-1205). */
@Serializable
data class UserResponse(
    val name: String,
    val displayName: String,
    val disabled: Boolean,
    val statusChangedBy: String?,
    val statusChangedAt: String?,
    val createdAt: String,
    val lastLoginAt: String,
    /** Sessions that are valid now. */
    val sessions: Int,
    /** Only in the answer to disabling: the sessions that went with it. */
    val sessionsEnded: Int? = null,
)

@Serializable data class UsersResponse(val users: List<UserResponse>)

/** A session by its handle; the identifier of a session is never in an answer. */
@Serializable
data class UserSessionResponse(val id: String, val createdAt: String, val lastUsedAt: String)

@Serializable data class UserSessionsResponse(val sessions: List<UserSessionResponse>)

@Serializable data class EndedSessionsResponse(val ended: Int)

private fun UserRecord.toResponse(ended: Int? = null) =
    UserResponse(
        name,
        displayName,
        disabled,
        statusChangedBy,
        statusChangedAt?.toString(),
        createdAt.toString(),
        lastLoginAt.toString(),
        sessions,
        ended,
    )

private fun SessionInfo.toResponse() =
    UserSessionResponse(ref, createdAt.toString(), lastUsedAt.toString())

/**
 * The people who signed in with OIDC and their sessions, for administrators (WI-1205, ADR-029
 * decision 9). They exist only when login is configured, and are for the role `admin` by token or
 * by session; a developer gets the same 403 whatever the name, so nothing is told about who exists.
 *
 * - `GET /api/v1/users`, `GET /api/v1/users/{name}`: name, display name, disabled or not, who
 *   changed that and when, last sign-in, valid sessions.
 * - `POST /api/v1/users/{name}/disable`, `.../enable`: disabling removes every session of the
 *   person in one transaction and refuses their sign-in until enabled; the name of an API token is
 *   409 `not_an_oidc_user`, an unknown name 404 `user_not_found`.
 * - `GET /api/v1/users/{name}/sessions`: handle, creation and last use of each valid session.
 * - `DELETE /api/v1/users/{name}/sessions[/{id}]`: ends all, or one by its handle.
 *
 * An administrator can disable or end their own session; the tokens of `API_TOKENS` are not touched
 * by any of it, which is the way back in.
 */
fun Application.configureUserRoutes() {
  val config: EngineConfig by dependencies
  if (config.oidc == null) return
  val admin: UserAdmin by dependencies

  routing {
    route("/api/v1/users") {
      authorized(Role.ADMIN) {
        get {
          val users = withContext(Dispatchers.IO) { admin.list() }
          call.respond(UsersResponse(users.map { it.toResponse() }))
        }
        route("/{name}") {
          get {
            val user = withContext(Dispatchers.IO) { admin.find(call.parameters.getOrFail("name")) }
            if (user == null) call.respondUserNotFound() else call.respond(user.toResponse())
          }
          post("/disable") {
            call.respondChange(
                withContext(Dispatchers.IO) {
                  admin.disable(call.parameters.getOrFail("name"), call.caller)
                }
            )
          }
          post("/enable") {
            call.respondChange(
                withContext(Dispatchers.IO) {
                  admin.enable(call.parameters.getOrFail("name"), call.caller)
                }
            )
          }
          route("/sessions") {
            get {
              val sessions =
                  withContext(Dispatchers.IO) { admin.sessions(call.parameters.getOrFail("name")) }
              if (sessions == null) call.respondUserNotFound()
              else call.respond(UserSessionsResponse(sessions.map { it.toResponse() }))
            }
            delete {
              val ended =
                  withContext(Dispatchers.IO) {
                    admin.endAll(call.parameters.getOrFail("name"), call.caller)
                  }
              if (ended == null) call.respondUserNotFound()
              else call.respond(EndedSessionsResponse(ended))
            }
            delete("/{id}") {
              val ended =
                  withContext(Dispatchers.IO) {
                    admin.endSession(
                        call.parameters.getOrFail("name"),
                        call.parameters.getOrFail("id"),
                        call.caller,
                    )
                  }
              if (ended) call.respond(HttpStatusCode.NoContent)
              else
                  call.respond(
                      HttpStatusCode.NotFound,
                      ErrorResponse("session_not_found", "找不到這個工作階段。"),
                  )
            }
          }
        }
      }
    }
  }
}

private suspend fun ApplicationCall.respondUserNotFound() =
    respond(HttpStatusCode.NotFound, ErrorResponse("user_not_found", "找不到這位使用者。"))

private suspend fun ApplicationCall.respondChange(change: UserChange) =
    when (change) {
      is UserChange.NotFound -> respondUserNotFound()
      is UserChange.TokenName ->
          respond(
              HttpStatusCode.Conflict,
              ErrorResponse("not_an_oidc_user", "這個名稱屬於 API token，不是以 OIDC 登入的使用者。"),
          )
      is UserChange.Changed -> respond(change.user.toResponse(change.sessionsEnded))
    }
