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
 * Shared resource management (WI-09, WI-40). Administrators only: a developer, even to look, is
 * answered 403 (ADR-012). Routes only convert HTTP to and from [ResourceAdmin], [ResourceCatalog]
 * and [ResourceRemoval]; the changer is recorded as the name of the caller's token.
 *
 * - `POST /api/v1/resources`: body `{name, capacity, type?, settings?, secretAlias?}`. 201 with the
 *   resource and its `Location`; 409 `resource_exists`; 422 `invalid_resource` (with `problem`);
 *   400 `bad_request`.
 * - `GET /api/v1/resources`: every resource with its holders, waiters and declaring definitions.
 * - `GET /api/v1/resources/{name}`: one resource, likewise; 404 `resource_not_found`. Waiters are
 *   in the order they will be served.
 * - `PATCH /api/v1/resources/{name}`: body `{capacity?, enabled?}`, at least one; `name` and `type`
 *   are refused. 200 with the resource; 404; 422 `invalid_resource`; 400.
 * - `DELETE /api/v1/resources/{name}`: 204; 404; 409 `resource_in_use` when runs hold it or wait
 *   for it. `?preview=true` answers 200 with what would be touched and changes nothing.
 * - `POST /api/v1/resources/{name}/holders/{runId}/release`: forces a holder to let go of this
 *   resource. 200; 404 `resource_not_found` or `not_a_holder`. The run itself is not stopped.
 */
fun Application.configureResourceRoutes() {
  val admin: ResourceAdmin by dependencies
  val catalog: ResourceCatalog by dependencies
  val removal: ResourceRemoval by dependencies
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
                    ErrorResponse(
                        "bad_request",
                        "請求內容需為 JSON：name 與 capacity，另可有 type、settings、secretAlias。",
                    ),
                )
              }
          val result =
              withContext(Dispatchers.IO) {
                admin.create(
                    request.name,
                    request.capacity,
                    call.caller,
                    request.type,
                    request.settings,
                    request.secretAlias,
                )
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
            is CreateResourceResult.Invalid -> call.respondInvalid(result.problem)
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
                      ErrorResponse(
                          "bad_request",
                          "請求內容需為 JSON：選填的 capacity 與 enabled（name 與 type 不可修改）。",
                      ),
                  )
                }
            val result =
                withContext(Dispatchers.IO) {
                  admin.update(
                      name,
                      request.capacity,
                      request.enabled,
                      call.caller,
                      request.name,
                      request.type,
                      request.settings,
                      request.secretAlias,
                  )
                }
            when (result) {
              is UpdateResourceResult.Updated ->
                  call.respond(viewOf(catalog, result.resource.name, clock))
              UpdateResourceResult.NotFound -> call.respondResourceNotFound()
              is UpdateResourceResult.Invalid -> call.respondInvalid(result.problem)
            }
          }

          delete {
            val name = call.parameters["name"].orEmpty()
            val preview =
                when (call.request.queryParameters["preview"]) {
                  null,
                  "false" -> false
                  "true" -> true
                  else ->
                      return@delete call.respond(
                          HttpStatusCode.BadRequest,
                          ErrorResponse("bad_request", "preview 只能是 true 或 false。"),
                      )
                }
            if (preview) {
              val shown =
                  withContext(Dispatchers.IO) { removal.preview(name) }
                      ?: return@delete call.respondResourceNotFound()
              return@delete call.respond(shown.toResponse())
            }
            when (val outcome = withContext(Dispatchers.IO) { removal.remove(name, call.caller) }) {
              RemovalOutcome.Removed -> call.respond(HttpStatusCode.NoContent)
              RemovalOutcome.NotFound -> call.respondResourceNotFound()
              is RemovalOutcome.InUse ->
                  call.respond(
                      HttpStatusCode.Conflict,
                      ResourceInUseResponse(
                          "resource_in_use",
                          "共享資源「$name」目前有 ${outcome.holders} 個持有者、${outcome.waiters} 個等待者，不能刪除；" +
                              "請等待它們結束、先停用資源，或強制釋放持有者。",
                          outcome.holders,
                          outcome.waiters,
                      ),
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

private suspend fun ApplicationCall.respondInvalid(problem: InvalidResource) =
    respond(
        HttpStatusCode.UnprocessableEntity,
        InvalidResourceResponse("invalid_resource", problem.message(), problem.problem),
    )

private fun RemovalPreview.toResponse() =
    RemovalPreviewResponse(resource, definitions, triggers, holders, waiters, inUse)

private fun InvalidResource.message() =
    when (this) {
      InvalidResource.NAME -> "資源名稱需為 1 至 100 個字元，只能使用英文字母、數字、「.」、「_」、「-」，且以英文字母或數字開頭。"
      InvalidResource.CAPACITY -> "容量必須是 1 以上的整數。"
      InvalidResource.NOTHING_TO_CHANGE -> "請提供要修改的 capacity 或 enabled。"
      InvalidResource.UNKNOWN_TYPE -> "資源型別不明；型別只能是 counter、file、jdbc-pool、openai-compatible。"
      InvalidResource.UNSUPPORTED_TYPE -> "這個資源型別尚未支援，目前只能建立 counter。"
      InvalidResource.INVALID_SETTINGS -> "這個資源型別沒有這些設定欄位。"
      InvalidResource.INVALID_SECRET_ALIAS -> "這個資源型別沒有機密別名，或別名不合規。"
      InvalidResource.IMMUTABLE_NAME -> "資源名稱建立後不能修改。"
      InvalidResource.IMMUTABLE_TYPE -> "資源型別建立後不能修改；要換型別請刪除後重新建立。"
    }
