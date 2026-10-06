package dev.lawlan.runline.engine

import dev.lawlan.runline.engine.artifact.ErrorResponse
import dev.lawlan.runline.engine.auth.Role
import dev.lawlan.runline.engine.auth.authorized
import dev.lawlan.runline.engine.resource.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shared resource management (WI-09). Administrators only: a developer, even to look, is answered
 * 403 (ADR-012). Routes only convert HTTP to and from [ResourceAdmin] and [ResourceCatalog]; the
 * changer is recorded as the name of the caller's token.
 *
 * - `POST /api/v1/resources`: body `{name, capacity}`. 201 with the resource and its `Location`;
 *   409 `resource_exists`; 422 `invalid_resource` (name or capacity); 400 `bad_request`.
 * - `GET /api/v1/resources`: every resource with its holders and waiters.
 * - `GET /api/v1/resources/{name}`: one resource with its holders and waiters; 404
 *   `resource_not_found`. Waiters are in the order they will be served.
 * - `PATCH /api/v1/resources/{name}`: body `{capacity?, enabled?}`, at least one. 200 with the
 *   resource; 404; 422 `invalid_resource` (capacity below one, or nothing to change); 400.
 * - `POST /api/v1/resources/{name}/holders/{runId}/release`: forces a holder to let go of this
 *   resource. 200; 404 `resource_not_found` or `not_a_holder`. The run itself is not stopped.
 */
fun Application.configureResourceRoutes() {
  val admin: ResourceAdmin by dependencies
  val catalog: ResourceCatalog by dependencies
  val clock: Clock by dependencies

  routing {
    route("/api/v1/resources") {
      authorized(Role.ADMIN) {
        post {
          val request =
              try {
                call.receive<CreateResourceRequest>()
              } catch (e: CancellationException) {
                throw e
              } catch (e: Exception) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("bad_request", "請求內容需為 JSON：name 與 capacity。"),
                )
              }
          val result =
              withContext(Dispatchers.IO) {
                admin.create(request.name, request.capacity, call.caller)
              }
          when (result) {
            is CreateResourceResult.Created -> {
              call.response.header(
                  HttpHeaders.Location,
                  "/api/v1/resources/${result.resource.name}",
              )
              call.respond(HttpStatusCode.Created, viewOf(catalog, result.resource.name, clock))
            }
            CreateResourceResult.AlreadyExists ->
                call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse("resource_exists", "已經有名為「${request.name}」的共享資源。"),
                )
            is CreateResourceResult.Invalid ->
                call.respond(
                    HttpStatusCode.UnprocessableEntity,
                    ErrorResponse("invalid_resource", result.problem.message()),
                )
          }
        }

        get {
          val views = withContext(Dispatchers.IO) { catalog.list() }
          call.respond(ResourceListResponse(views.map { it.toResponse(clock) }))
        }

        route("/{name}") {
          get {
            val view =
                withContext(Dispatchers.IO) { catalog.find(call.parameters["name"].orEmpty()) }
                    ?: return@get call.respondResourceNotFound()
            call.respond(view.toResponse(clock))
          }

          patch {
            val name = call.parameters["name"].orEmpty()
            val request =
                try {
                  call.receive<UpdateResourceRequest>()
                } catch (e: CancellationException) {
                  throw e
                } catch (e: Exception) {
                  return@patch call.respond(
                      HttpStatusCode.BadRequest,
                      ErrorResponse("bad_request", "請求內容需為 JSON：選填的 capacity 與 enabled。"),
                  )
                }
            val result =
                withContext(Dispatchers.IO) {
                  admin.update(name, request.capacity, request.enabled, call.caller)
                }
            when (result) {
              is UpdateResourceResult.Updated ->
                  call.respond(viewOf(catalog, result.resource.name, clock))
              UpdateResourceResult.NotFound -> call.respondResourceNotFound()
              is UpdateResourceResult.Invalid ->
                  call.respond(
                      HttpStatusCode.UnprocessableEntity,
                      ErrorResponse("invalid_resource", result.problem.message()),
                  )
            }
          }

          post("/holders/{runId}/release") {
            val name = call.parameters["name"].orEmpty()
            val runId =
                call.parameters["runId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            val outcome =
                if (runId == null) {
                  withContext(Dispatchers.IO) { catalog.find(name) }
                      ?.let { ForceReleaseOutcome.NotHeld } ?: ForceReleaseOutcome.ResourceNotFound
                } else {
                  withContext(Dispatchers.IO) { catalog.forceRelease(name, runId, call.caller) }
                }
            when (outcome) {
              is ForceReleaseOutcome.Released ->
                  call.respond(
                      ForceReleaseResponse(
                          name,
                          outcome.holder.runId.toString(),
                          outcome.holder.pipelineName,
                          outcome.holder.since.toString(),
                      )
                  )
              ForceReleaseOutcome.ResourceNotFound -> call.respondResourceNotFound()
              ForceReleaseOutcome.NotHeld ->
                  call.respond(
                      HttpStatusCode.NotFound,
                      ErrorResponse("not_a_holder", "這個 run 目前沒有持有共享資源「$name」。"),
                  )
            }
          }
        }
      }
    }
  }
}

private suspend fun viewOf(catalog: ResourceCatalog, name: String, clock: Clock) =
    withContext(Dispatchers.IO) { checkNotNull(catalog.find(name)) }.toResponse(clock)

private suspend fun ApplicationCall.respondResourceNotFound() =
    respond(HttpStatusCode.NotFound, ErrorResponse("resource_not_found", "找不到這個共享資源。"))

private fun InvalidResource.message() =
    when (this) {
      InvalidResource.NAME -> "資源名稱需為 1 至 100 個字元，只能使用英文字母、數字、「.」、「_」、「-」，且以英文字母或數字開頭。"
      InvalidResource.CAPACITY -> "容量必須是 1 以上的整數。"
      InvalidResource.NOTHING_TO_CHANGE -> "請提供要修改的 capacity 或 enabled。"
    }
