package dev.lawlan.runline.engine

import dev.lawlan.runline.engine.artifact.ErrorResponse
import dev.lawlan.runline.engine.artifact.VersionOutcome
import dev.lawlan.runline.engine.auth.Role
import dev.lawlan.runline.engine.auth.authorized
import dev.lawlan.runline.engine.resource.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.util.getOrFail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Which resource each role of a pipeline gets (WI-1012, ADR-025). Developers and administrators
 * alike, on the versions they may see (a version they may not see is answered as one that does not
 * exist); creating, changing and deleting resources stays with administrators. Routes only convert
 * HTTP to and from [ResourceBindings]. A binding belongs to an uploader's pipeline (uploader and
 * name); the version in the path says whose and which roles and types it is checked against, so a
 * developer reaches only their own and an administrator names the uploader.
 *
 * - `GET /api/v1/definitions/{contentHash}/{pipeline}/resource-bindings[?uploader=]`: every role
 *   with its type, purpose, binding, the resource it gets and that resource's state, and the
 *   resources it could be bound to; and the bindings no role of the version claims. 404
 *   `definition_not_found`; 409 `ambiguous_version`.
 * - `PUT .../resource-bindings/{role}[?uploader=]`: body `{resource}`. 200 with the listing; 404
 *   `resource_not_found`; 422 `invalid_binding` (`unknown_role`, `type_mismatch`); 400.
 * - `DELETE .../resource-bindings/{role}[?uploader=]`: removes the binding, whether or not a role
 *   of the version still declares it; 200 with the listing, also when a declared role had none; 404
 *   `role_not_found` for a role neither declared nor bound.
 */
fun Application.configureResourceBindingRoutes() {
  val bindings: ResourceBindings by dependencies

  routing {
    route("/api/v1/definitions/{contentHash}/{pipeline}/resource-bindings") {
      authorized(Role.DEVELOPER) {
        get {
          val result =
              withContext(Dispatchers.IO) {
                bindings.view(
                    call.parameters.getOrFail("contentHash"),
                    call.request.queryParameters["uploader"],
                    call.parameters.getOrFail("pipeline"),
                    call.caller,
                )
              }
          call.respondBindings(result)
        }

        put("/{role}") {
          val request =
              try {
                call.receive<BindRoleRequest>()
              } catch (e: CancellationException) {
                throw e
              } catch (e: Exception) {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("bad_request", "請求內容需為 JSON：{\"resource\": \"資源名稱\"}。"),
                )
              }
          val result =
              withContext(Dispatchers.IO) {
                bindings.bind(
                    call.parameters.getOrFail("contentHash"),
                    call.request.queryParameters["uploader"],
                    call.parameters.getOrFail("pipeline"),
                    call.parameters.getOrFail("role"),
                    request.resource,
                    call.caller,
                )
              }
          call.respondBindings(result)
        }

        delete("/{role}") {
          val result =
              withContext(Dispatchers.IO) {
                bindings.unbind(
                    call.parameters.getOrFail("contentHash"),
                    call.request.queryParameters["uploader"],
                    call.parameters.getOrFail("pipeline"),
                    call.parameters.getOrFail("role"),
                    call.caller,
                )
              }
          call.respondBindings(result)
        }
      }
    }
  }
}

private suspend fun ApplicationCall.respondBindings(result: BindingsResult) =
    when (result) {
      is BindingsResult.Found -> respond(result.view.toResponse())
      is BindingsResult.Ambiguous ->
          respondAmbiguousVersion(VersionOutcome.Ambiguous(result.uploaders))
      BindingsResult.DefinitionNotFound ->
          respond(
              HttpStatusCode.NotFound,
              ErrorResponse("definition_not_found", "找不到這個 pipeline 定義。"),
          )
      BindingsResult.RoleNotFound ->
          respond(HttpStatusCode.NotFound, ErrorResponse("role_not_found", "找不到這個資源角色。"))
      BindingsResult.ResourceNotFound ->
          respond(HttpStatusCode.NotFound, ErrorResponse("resource_not_found", "找不到這個共享資源。"))
      is BindingsResult.Invalid ->
          respond(
              HttpStatusCode.UnprocessableEntity,
              InvalidBindingResponse(
                  "invalid_binding",
                  when (result.problem) {
                    InvalidBinding.UNKNOWN_ROLE -> "這個版本的 pipeline 沒有宣告這個資源角色。"
                    InvalidBinding.TYPE_MISMATCH -> "資源的型別與角色宣告的型別不同，不能綁定。"
                  },
                  result.problem.problem,
              ),
          )
    }
