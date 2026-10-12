package dev.lawlan.runline.engine

import dev.lawlan.runline.engine.artifact.ErrorResponse
import dev.lawlan.runline.engine.artifact.VersionOutcome
import dev.lawlan.runline.engine.auth.Role
import dev.lawlan.runline.engine.auth.authorized
import dev.lawlan.runline.engine.view.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.util.getOrFail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The read-only views of a pipeline (WI-1301, ADR-033). Routes only convert HTTP to and from
 * [ViewCatalog]; the version is named and found as for resource bindings, so that a developer
 * reaches their own versions and an administrator names the uploader.
 *
 * - `GET /api/v1/definitions/{contentHash}/{pipeline}/views[?uploader=]`: the declared views, each
 *   with whether it can be read now. Runs no query. 404 `definition_not_found`; 409
 *   `ambiguous_version`.
 * - `GET
 *   /api/v1/definitions/{contentHash}/{pipeline}/views/{view}[?uploader=&limit=&offset=&param.<name>=]`:
 *   one page of the view's rows (see 08-api).
 */
fun Application.configureViewRoutes() {
  val catalog: ViewCatalog by dependencies
  val reader: ViewReader by dependencies

  routing {
    route("/api/v1/definitions/{contentHash}/{pipeline}/views") {
      authorized(Role.DEVELOPER) {
        get {
          val result =
              withContext(Dispatchers.IO) {
                catalog.list(
                    call.parameters.getOrFail("contentHash"),
                    call.request.queryParameters["uploader"],
                    call.parameters.getOrFail("pipeline"),
                    call.caller,
                )
              }
          when (result) {
            is ViewListResult.Found -> call.respond(result.toResponse())
            is ViewListResult.Ambiguous ->
                call.respondAmbiguousVersion(VersionOutcome.Ambiguous(result.uploaders))
            ViewListResult.NotFound -> call.respondDefinitionNotFound()
          }
        }
      }
    }
    route("/api/v1/definitions/{contentHash}/{pipeline}/views/{view}") {
      authorized(Role.DEVELOPER) {
        get {
          val query = call.request.queryParameters
          val result =
              reader.read(
                  call.parameters.getOrFail("contentHash"),
                  query["uploader"],
                  call.parameters.getOrFail("pipeline"),
                  call.parameters.getOrFail("view"),
                  ViewRequest(
                      query
                          .entries()
                          .filter { it.key.startsWith(PARAMETER_PREFIX) }
                          .associate { it.key.removePrefix(PARAMETER_PREFIX) to it.value },
                      query["limit"],
                      query["offset"],
                  ),
                  call.caller,
              )
          call.respondRead(result)
        }
      }
    }
  }
}

/** 404 `definition_not_found`, the one answer for a version that is another's or not there. */
private suspend fun ApplicationCall.respondDefinitionNotFound() =
    respond(
        HttpStatusCode.NotFound,
        ErrorResponse("definition_not_found", "找不到這個 pipeline 定義。"),
    )

/** The query parameters that are values of the view's own parameters begin with this. */
private const val PARAMETER_PREFIX = "param."

private suspend fun ApplicationCall.respondRead(result: ViewReadResult) {
  when (result) {
    is ViewReadResult.Page -> {
      response.header(HttpHeaders.CacheControl, "no-store")
      respond(result.toResponse())
    }
    is ViewReadResult.Ambiguous ->
        respondAmbiguousVersion(VersionOutcome.Ambiguous(result.uploaders))
    ViewReadResult.DefinitionNotFound -> respondDefinitionNotFound()
    ViewReadResult.ViewNotFound ->
        respond(
            HttpStatusCode.NotFound,
            ErrorResponse("view_not_found", "這個 pipeline 沒有宣告這個 view。"),
        )
    is ViewReadResult.InvalidParameter ->
        respond(
            HttpStatusCode.BadRequest,
            InvalidViewParameterResponse(
                "invalid_view_parameter",
                "參數「${result.refusal.name}」不合格（${result.refusal.problem.wire}）。",
                result.refusal.name,
                result.refusal.problem.wire,
            ),
        )
    is ViewReadResult.ResourceUnavailable ->
        respond(
            HttpStatusCode.Conflict,
            ViewResourceUnavailableResponse(
                "view_resource_unavailable",
                "這個 view 掛載的資源角色「${result.role}」目前不能使用（${result.problem.label}）。",
                result.problem.wire,
                result.role,
            ),
        )
    ViewReadResult.QueryRejected ->
        respond(
            HttpStatusCode.UnprocessableEntity,
            ErrorResponse(
                "view_query_rejected",
                "這個 view 的查詢不是單一的唯讀查詢，或資料庫不接受它；Engine 沒有執行任何會修改資料的動作。",
            ),
        )
    ViewReadResult.SchemaMismatch ->
        respond(
            HttpStatusCode.Conflict,
            ErrorResponse(
                "view_schema_mismatch",
                "這個 view 的查詢與資料庫的結構不符（資料表或欄位不存在，或資料庫尚未建立）；" + "待 pipeline 下一次 run 升級結構後再試。",
            ),
        )
    ViewReadResult.TooLarge ->
        respond(
            HttpStatusCode.UnprocessableEntity,
            ErrorResponse("view_result_too_large", "這一頁的結果超過資源設定的列數或大小上限；請縮小查詢範圍或每頁筆數。"),
        )
    ViewReadResult.Timeout ->
        respond(
            HttpStatusCode.GatewayTimeout,
            ErrorResponse("view_timeout", "這個 view 的查詢超過資源設定的逾時時間，已被中止。"),
        )
    ViewReadResult.ConnectionFailed ->
        respond(
            HttpStatusCode.BadGateway,
            ErrorResponse("view_connection_failed", "無法連到這個 view 掛載的資料庫，或資源目前不能使用。"),
        )
    is ViewReadResult.Failed -> {
      val errorId = logUnexpectedError("reading a view", ViewReadFailure(result))
      respond(
          HttpStatusCode.InternalServerError,
          InternalErrorResponse("internal_error", internalErrorMessage(errorId), errorId),
      )
    }
    ViewReadResult.Busy ->
        respond(
            HttpStatusCode.TooManyRequests,
            ErrorResponse("view_busy", "這個資源同時進行的 view 讀取已達上限，請稍後再試。"),
        )
  }
}

/**
 * What the log has of an unexpected failure of a read: the SQLState and the kind of exception and
 * nothing of the statement or its values, which the words of a database can hold.
 */
private class ViewReadFailure(failed: ViewReadResult.Failed) :
    RuntimeException("view read failed: sqlState=${failed.sqlState} cause=${failed.causeClass}")
