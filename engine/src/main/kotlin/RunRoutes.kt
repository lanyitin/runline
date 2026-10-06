package dev.lawlan.runline.engine

import dev.lawlan.runline.engine.artifact.ErrorResponse
import dev.lawlan.runline.engine.artifact.UnsafeExecutionSettings
import dev.lawlan.runline.engine.artifact.visibility
import dev.lawlan.runline.engine.auth.Role
import dev.lawlan.runline.engine.auth.authorized
import dev.lawlan.runline.engine.resource.ResourceProblemDoc
import dev.lawlan.runline.engine.resource.ResourceProblemKind
import dev.lawlan.runline.engine.resource.ResourcesUnavailableResponse
import dev.lawlan.runline.engine.run.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.util.getOrFail
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Run API (WI-08). Routes only convert HTTP to and from [RunService], [RunCatalog] and
 * [UnsafeExecutionSettings]; every rule lives there. A run or pipeline the caller may not see is
 * answered exactly as one that does not exist.
 *
 * - `POST /api/v1/runs` (developer): body `{contentHash, pipeline, parameters}`. 201 with the run
 *   and its `Location`; 404 `definition_not_found`; 422 `invalid_parameters` naming each parameter;
 *   409 `unsafe_not_allowed`; 409 `resources_unavailable` naming each shared resource the pipeline
 *   declares that is not defined (`unknown`) or disabled (`disabled`); 400 `bad_request`.
 * - `GET /api/v1/runs[?pipeline=&limit=]` (developer): own runs, newest first; administrators all.
 * - `GET /api/v1/runs/{runId}` (developer): state, source, parameters, result. 404 when absent.
 * - `POST /api/v1/runs/{runId}/cancel` (developer): 200 when it had not started and ended
 *   cancelled; 202 when asked to stop (cooperative); 409 `already_finished`; 404.
 * - `GET /api/v1/runs/{runId}/log[?after=&limit=]` (developer): stored log entries.
 * - `GET /api/v1/runs/{runId}/log/stream[?after=]` (developer, WebSocket): entries as JSON text
 *   frames until the run ends, then the socket is closed normally.
 * - `PUT /api/v1/definitions/{contentHash}/{pipeline}/unsafe-execution` (administrator): body
 *   `{allow}`; 200 with the setting, who set it and when; 404.
 */
fun Application.configureRunRoutes() {
  val runs: RunService by dependencies
  val catalog: RunCatalog by dependencies
  val follower: RunLogFollower by dependencies
  val unsafeSettings: UnsafeExecutionSettings by dependencies

  routing {
    route("/api/v1") {
      authorized(Role.DEVELOPER) {
        post("/runs") {
          val request =
              try {
                call.receive<CreateRunRequest>()
              } catch (e: CancellationException) {
                throw e
              } catch (e: Exception) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(
                        "bad_request",
                        "請求內容需為 JSON：contentHash、pipeline，以及選填的 parameters。",
                    ),
                )
              }
          val caller = call.caller
          val result =
              withContext(Dispatchers.IO) {
                runs.create(
                    CreateRun(
                        request.contentHash,
                        request.pipeline,
                        request.parameters,
                        RunSource.Manual(caller.name),
                        caller.visibility,
                    )
                )
              }
          when (result) {
            is CreateRunResult.Accepted -> {
              call.response.header(HttpHeaders.Location, "/api/v1/runs/${result.run.id}")
              call.respond(HttpStatusCode.Created, result.run.toResponse())
            }
            CreateRunResult.DefinitionNotFound ->
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorResponse("definition_not_found", "找不到這個 pipeline 定義。"),
                )
            is CreateRunResult.InvalidParameters ->
                call.respond(HttpStatusCode.UnprocessableEntity, result.toResponse())
            is CreateRunResult.ResourcesUnavailable ->
                call.respond(HttpStatusCode.Conflict, result.toResponse())
            is CreateRunResult.UnsafeNotAllowed ->
                call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse(
                        "unsafe_not_allowed",
                        "pipeline「${result.pipelineName}」被判定為 unsafe，且管理員尚未允許它以 unsafe 執行。",
                    ),
                )
          }
        }

        get("/runs") {
          val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT)
          val pipeline = call.request.queryParameters["pipeline"]
          val found =
              withContext(Dispatchers.IO) {
                catalog.list(call.caller.visibility, pipeline, limit.coerceIn(1, MAX_LIMIT))
              }
          call.respond(RunListResponse(found.map { it.toResponse() }))
        }

        route("/runs/{runId}") {
          get {
            val id = call.runId() ?: return@get call.respondRunNotFound()
            val run =
                withContext(Dispatchers.IO) { catalog.find(id, call.caller.visibility) }
                    ?: return@get call.respondRunNotFound()
            call.respond(run.toResponse())
          }

          post("/cancel") {
            val id = call.runId() ?: return@post call.respondRunNotFound()
            val visibility = call.caller.visibility
            when (val result = withContext(Dispatchers.IO) { runs.cancel(id, visibility) }) {
              CancelResult.NotFound -> call.respondRunNotFound()
              is CancelResult.AlreadyFinished ->
                  call.respond(
                      HttpStatusCode.Conflict,
                      ErrorResponse("already_finished", "這個 run 已經結束（${result.state}），不能取消。"),
                  )
              CancelResult.Cancelled ->
                  call.respond(HttpStatusCode.OK, call.cancelResponse(catalog, id, "cancelled"))
              CancelResult.CancellationRequested ->
                  call.respond(
                      HttpStatusCode.Accepted,
                      call.cancelResponse(catalog, id, "requested"),
                  )
            }
          }

          get("/log") {
            val id = call.runId() ?: return@get call.respondRunNotFound()
            val after = call.request.queryParameters["after"]?.toLongOrNull() ?: 0L
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LOG_LIMIT
            val entries =
                withContext(Dispatchers.IO) {
                  catalog.log(id, call.caller.visibility, after, limit.coerceIn(1, MAX_LOG_LIMIT))
                } ?: return@get call.respondRunNotFound()
            call.respond(
                LogResponse(entries.map { it.toDoc() }, entries.lastOrNull()?.seq ?: after)
            )
          }

          route("/log/stream") {
            // Refuse the handshake like any other request, before the connection is upgraded.
            intercept(ApplicationCallPipeline.Call) {
              val id = call.runId()
              val visible =
                  id != null &&
                      withContext(Dispatchers.IO) { catalog.find(id, call.caller.visibility) } !=
                          null
              if (!visible) {
                call.respondRunNotFound()
                finish()
              }
            }
            webSocket {
              val id = checkNotNull(call.runId())
              val after = call.request.queryParameters["after"]?.toLongOrNull() ?: 0L
              try {
                follower.follow(id, call.caller.visibility, after) { entry ->
                  send(Frame.Text(Json.encodeToString(entry.toDoc())))
                }
                close(CloseReason(CloseReason.Codes.NORMAL, "run ended"))
              } catch (e: CancellationException) {
                throw e
              } catch (e: ClosedSendChannelException) {
                throw e
              } catch (e: Exception) {
                // The cause stays in the log; the peer is told only how to find it.
                val errorId = logUnexpectedError("streaming the log of run $id", e)
                close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "internal error $errorId"))
              }
            }
          }
        }
      }

      authorized(Role.ADMIN) {
        put("/definitions/{contentHash}/{pipeline}/unsafe-execution") {
          val contentHash = call.parameters.getOrFail("contentHash")
          val pipeline = call.parameters.getOrFail("pipeline")
          val request =
              try {
                call.receive<UnsafeExecutionRequest>()
              } catch (e: CancellationException) {
                throw e
              } catch (e: Exception) {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("bad_request", "請求內容需為 JSON：{\"allow\": true 或 false}。"),
                )
              }
          val updated =
              withContext(Dispatchers.IO) {
                unsafeSettings.set(contentHash, pipeline, request.allow, call.caller)
              }
          if (updated == null) {
            call.respond(
                HttpStatusCode.NotFound,
                ErrorResponse("definition_not_found", "找不到這個 pipeline 定義。"),
            )
          } else {
            call.respond(updated.toUnsafeResponse())
          }
        }
      }
    }
  }
}

private const val DEFAULT_LIMIT = 50
private const val MAX_LIMIT = 200
private const val DEFAULT_LOG_LIMIT = 500
private const val MAX_LOG_LIMIT = 2000

private fun ApplicationCall.runId(): UUID? =
    parameters["runId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }

private suspend fun ApplicationCall.respondRunNotFound() =
    respond(HttpStatusCode.NotFound, ErrorResponse("run_not_found", "找不到這個 run。"))

private suspend fun ApplicationCall.cancelResponse(
    catalog: RunCatalog,
    id: UUID,
    cancellation: String,
): CancelResponse {
  val state = withContext(Dispatchers.IO) { catalog.find(id, caller.visibility) }?.state
  return CancelResponse(id.toString(), state?.name ?: RunState.CANCELLED.name, cancellation)
}

private fun CreateRunResult.ResourcesUnavailable.toResponse() =
    ResourcesUnavailableResponse(
        "resources_unavailable",
        "pipeline 宣告的共享資源目前無法使用：" +
            problems.joinToString("；") {
              when (it.kind) {
                ResourceProblemKind.UNKNOWN -> "${it.name}（尚未定義）"
                ResourceProblemKind.DISABLED -> "${it.name}（已停用）"
              }
            } +
            "。請管理員定義或啟用後再建立 run。",
        problems.map {
          ResourceProblemDoc(
              it.name,
              when (it.kind) {
                ResourceProblemKind.UNKNOWN -> "unknown"
                ResourceProblemKind.DISABLED -> "disabled"
              },
          )
        },
    )

private fun CreateRunResult.InvalidParameters.toResponse() =
    ParameterErrorResponse(
        "invalid_parameters",
        "參數與 pipeline 的宣告不符：" +
            problems.joinToString("；") {
              when (it.kind) {
                ParameterProblemKind.MISSING -> "缺少必填參數 ${it.name}"
                ParameterProblemKind.UNDECLARED -> "未宣告的參數 ${it.name}"
              }
            },
        problems.map {
          ParameterProblemDoc(
              it.name,
              when (it.kind) {
                ParameterProblemKind.MISSING -> "missing"
                ParameterProblemKind.UNDECLARED -> "undeclared"
              },
          )
        },
    )
