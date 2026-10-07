package dev.lawlan.runline.engine

import dev.lawlan.runline.engine.artifact.ErrorResponse
import dev.lawlan.runline.engine.artifact.VersionOutcome
import dev.lawlan.runline.engine.auth.Role
import dev.lawlan.runline.engine.auth.authorized
import dev.lawlan.runline.engine.run.ParameterErrorResponse
import dev.lawlan.runline.engine.run.ParameterProblem
import dev.lawlan.runline.engine.run.ParameterProblemDoc
import dev.lawlan.runline.engine.run.ParameterProblemKind
import dev.lawlan.runline.engine.trigger.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Trigger management (WI-07, ADR-005). Administrators only: a developer, even to look, is answered
 * 403 (ADR-012). Routes only convert HTTP to and from [TriggerAdmin] and [TriggerCatalog]; the
 * changer is recorded as the name of the caller's token. A webhook's secret is shown only in the
 * response that creates the trigger or rotates the secret, and in no other response.
 *
 * - `POST /api/v1/triggers`: body `{name, kind: "cron"|"webhook", contentHash, uploader?, pipeline,
 *   parameters?, cron?, timeZone?, enabled?}`; 409 `ambiguous_version` when several uploaders have
 *   the content and none is named. 201 with `{trigger, secret}` and the `Location` (`secret` only
 *   for a webhook); 404 `definition_not_found`; 409 `trigger_exists`; 422 `invalid_parameters`
 *   (naming each parameter) or `invalid_trigger` (the `problem` says which part); 400
 *   `bad_request`.
 * - `GET /api/v1/triggers`, `GET /api/v1/triggers/{name}`: triggers; 404 `trigger_not_found`.
 * - `PATCH /api/v1/triggers/{name}`: body with at least one of `{contentHash, uploader, pipeline,
 *   parameters, enabled, cron, timeZone}`; changes the binding (parameters are checked against the
 *   version it moves to), parameters, schedule, enabled. 200 with the trigger; the same refusals as
 *   creation.
 * - `DELETE /api/v1/triggers/{name}`: unbinds; 204 or 404. The record of firings goes with it.
 * - `POST /api/v1/triggers/{name}/rotate-secret`: 200 with `{trigger, secret}`; the old secret is
 *   void at once; 409 `not_a_webhook`; 404.
 * - `GET /api/v1/triggers/{name}/firings[?limit=]`: the recent firings, newest first, each with its
 *   time, result (`run_created`, `refused`, `failed`, ...), the reason it was refused and its run.
 */
fun Application.configureTriggerRoutes() {
  val admin: TriggerAdmin by dependencies
  val catalog: TriggerCatalog by dependencies

  routing {
    route("/api/v1/triggers") {
      authorized(Role.ADMIN) {
        post {
          val request =
              try {
                call.receive<CreateTriggerRequest>()
              } catch (e: CancellationException) {
                throw e
              } catch (e: Exception) {
                return@post call.respondBadRequest(
                    "請求內容需為 JSON：name、kind（cron 或 webhook）、contentHash、pipeline，以及選填的 parameters、cron、timeZone、enabled。"
                )
              }
          val kind =
              TriggerKind.entries.find { it.name.equals(request.kind, ignoreCase = true) }
                  ?: return@post call.respondBadRequest("kind 必須是 cron 或 webhook。")
          val result =
              withContext(Dispatchers.IO) {
                admin.create(
                    CreateTrigger(
                        request.name,
                        kind,
                        request.contentHash,
                        request.pipeline,
                        request.parameters,
                        request.cron,
                        request.timeZone,
                        request.enabled,
                        request.uploader,
                    ),
                    call.caller,
                )
              }
          when (result) {
            is CreateTriggerResult.Created -> {
              call.response.header(HttpHeaders.Location, "/api/v1/triggers/${result.trigger.name}")
              call.respond(
                  HttpStatusCode.Created,
                  TriggerSecretResponse(viewOf(catalog, result.trigger.name), result.secret),
              )
            }
            CreateTriggerResult.NameTaken ->
                call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse("trigger_exists", "已經有名為「${request.name}」的 trigger。"),
                )
            CreateTriggerResult.DefinitionNotFound -> call.respondDefinitionNotFound()
            is CreateTriggerResult.AmbiguousVersion ->
                call.respondAmbiguousVersion(VersionOutcome.Ambiguous(result.uploaders))
            is CreateTriggerResult.InvalidParameters ->
                call.respondInvalidParameters(result.problems)
            is CreateTriggerResult.Invalid -> call.respondInvalid(result.problem)
          }
        }

        get {
          val views = withContext(Dispatchers.IO) { catalog.list() }
          call.respond(TriggerListResponse(views.map { it.toResponse() }))
        }

        route("/{name}") {
          get {
            val view =
                withContext(Dispatchers.IO) { catalog.find(call.parameters["name"].orEmpty()) }
                    ?: return@get call.respondTriggerNotFound()
            call.respond(view.toResponse())
          }

          patch {
            val name = call.parameters["name"].orEmpty()
            val request =
                try {
                  call.receive<UpdateTriggerRequest>()
                } catch (e: CancellationException) {
                  throw e
                } catch (e: Exception) {
                  return@patch call.respondBadRequest(
                      "請求內容需為 JSON：選填的 contentHash、pipeline、parameters、enabled、cron、timeZone。"
                  )
                }
            val result =
                withContext(Dispatchers.IO) {
                  admin.update(
                      name,
                      UpdateTrigger(
                          request.contentHash,
                          request.uploader,
                          request.pipeline,
                          request.parameters,
                          request.enabled,
                          request.cron,
                          request.timeZone,
                      ),
                      call.caller,
                  )
                }
            when (result) {
              is UpdateTriggerResult.Updated -> call.respond(viewOf(catalog, result.trigger.name))
              UpdateTriggerResult.NotFound -> call.respondTriggerNotFound()
              UpdateTriggerResult.DefinitionNotFound -> call.respondDefinitionNotFound()
              is UpdateTriggerResult.AmbiguousVersion ->
                  call.respondAmbiguousVersion(VersionOutcome.Ambiguous(result.uploaders))
              is UpdateTriggerResult.InvalidParameters ->
                  call.respondInvalidParameters(result.problems)
              is UpdateTriggerResult.Invalid -> call.respondInvalid(result.problem)
            }
          }

          delete {
            val name = call.parameters["name"].orEmpty()
            if (withContext(Dispatchers.IO) { admin.delete(name, call.caller) }) {
              call.respond(HttpStatusCode.NoContent)
            } else {
              call.respondTriggerNotFound()
            }
          }

          post("/rotate-secret") {
            val name = call.parameters["name"].orEmpty()
            when (
                val result = withContext(Dispatchers.IO) { admin.rotateSecret(name, call.caller) }
            ) {
              is RotateSecretResult.Rotated ->
                  call.respond(
                      TriggerSecretResponse(viewOf(catalog, result.trigger.name), result.secret)
                  )
              RotateSecretResult.NotFound -> call.respondTriggerNotFound()
              RotateSecretResult.NotAWebhook ->
                  call.respond(
                      HttpStatusCode.Conflict,
                      ErrorResponse("not_a_webhook", "只有 webhook trigger 有密鑰可以輪替。"),
                  )
            }
          }

          get("/firings") {
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT
            val firings =
                withContext(Dispatchers.IO) {
                  catalog.firings(call.parameters["name"].orEmpty(), limit.coerceIn(1, MAX_LIMIT))
                } ?: return@get call.respondTriggerNotFound()
            call.respond(FiringListResponse(firings.map { it.toResponse() }))
          }
        }
      }
    }
  }
}

private const val DEFAULT_LIMIT = 50
private const val MAX_LIMIT = 200

private suspend fun viewOf(catalog: TriggerCatalog, name: String): TriggerResponse =
    withContext(Dispatchers.IO) { checkNotNull(catalog.find(name)) }.toResponse()

private suspend fun ApplicationCall.respondBadRequest(message: String) =
    respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", message))

private suspend fun ApplicationCall.respondTriggerNotFound() =
    respond(HttpStatusCode.NotFound, ErrorResponse("trigger_not_found", "找不到這個 trigger。"))

private suspend fun ApplicationCall.respondDefinitionNotFound() =
    respond(HttpStatusCode.NotFound, ErrorResponse("definition_not_found", "找不到這個 pipeline 定義。"))

private suspend fun ApplicationCall.respondInvalidParameters(problems: List<ParameterProblem>) =
    respond(
        HttpStatusCode.UnprocessableEntity,
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
        ),
    )

private suspend fun ApplicationCall.respondInvalid(problem: InvalidTrigger) =
    respond(
        HttpStatusCode.UnprocessableEntity,
        when (problem) {
          InvalidTrigger.NAME ->
              InvalidTriggerResponse(
                  "invalid_trigger",
                  "name",
                  "名稱需為 1 至 100 個字元，只能使用英文字母、數字、「.」、「_」、「-」，且以英文字母或數字開頭。",
              )
          InvalidTrigger.CRON_REQUIRED ->
              InvalidTriggerResponse(
                  "invalid_trigger",
                  "cron_required",
                  "cron trigger 需要提供 cron 表達式。",
              )
          InvalidTrigger.CRON_EXPRESSION ->
              InvalidTriggerResponse("invalid_trigger", "cron_expression", "cron 不是標準的五欄表達式。")
          InvalidTrigger.TIME_ZONE ->
              InvalidTriggerResponse(
                  "invalid_trigger",
                  "time_zone",
                  "timeZone 不是 IANA 時區識別碼（例如 Asia/Taipei）。",
              )
          InvalidTrigger.SCHEDULE_ON_WEBHOOK ->
              InvalidTriggerResponse(
                  "invalid_trigger",
                  "schedule_not_allowed",
                  "webhook trigger 不能設定 cron 或 timeZone。",
              )
          InvalidTrigger.NOTHING_TO_CHANGE ->
              InvalidTriggerResponse("invalid_trigger", "nothing_to_change", "請提供要修改的欄位。")
        },
    )
