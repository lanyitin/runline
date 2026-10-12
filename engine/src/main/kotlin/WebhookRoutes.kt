package dev.lawlan.runline.engine

import dev.lawlan.runline.engine.artifact.ErrorResponse
import dev.lawlan.runline.engine.run.ParameterErrorResponse
import dev.lawlan.runline.engine.run.ParameterProblemDoc
import dev.lawlan.runline.engine.trigger.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The webhook entry (WI-07, ADR-005): `POST /api/v1/webhooks/{name}`. It is not behind the API's
 * Bearer token; the call proves itself with the trigger's own secret.
 *
 * - [WEBHOOK_SECRET_HEADER]: the trigger's secret. [WEBHOOK_DELIVERY_HEADER]: the caller's
 *   identifier of this delivery, 1 to 200 visible ASCII characters, unique per trigger; a delivery
 *   sent again, even at the same moment, creates no second run.
 * - 202 `{"status":"accepted"}` once authenticated, whatever becomes of the run (a refusal to
 *   create it is for the administrator to see) and for a delivery already received. Nothing else
 *   about the run is in the answer.
 * - 401 `unauthorized`, always the same answer, when the secret is missing or wrong, the trigger is
 *   disabled, is not a webhook or does not exist.
 * - 400 `invalid_delivery_id` when the call is authenticated but the delivery identifier is missing
 *   or malformed; 500 `internal_error` when something unexpected happened, after which the same
 *   delivery can be sent again.
 *
 * The request body is never read: it does not influence the run in any way.
 */
fun Application.configureWebhookRoutes() {
  val receiver: WebhookReceiver by dependencies

  routing {
    post("/api/v1/webhooks/{name}") {
      val result =
          withContext(Dispatchers.IO) {
            receiver.receive(
                call.parameters["name"].orEmpty(),
                call.request.headers[WEBHOOK_SECRET_HEADER],
                call.request.headers[WEBHOOK_DELIVERY_HEADER],
            ) {
              call.request.receiveChannel().toInputStream()
            }
          }
      when (result) {
        WebhookResult.Accepted -> call.respond(HttpStatusCode.Accepted, WebhookAccepted())
        WebhookResult.Unauthorized ->
            call.respond(
                HttpStatusCode.Unauthorized,
                ErrorResponse("unauthorized", "未通過驗證。"),
            )
        WebhookResult.InvalidDelivery ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(
                    "invalid_delivery_id",
                    "請在 $WEBHOOK_DELIVERY_HEADER 標頭提供 1 至 200 個可見 ASCII 字元的 delivery 識別碼。",
                ),
            )
        is WebhookResult.InvalidBody ->
            call.respond(
                HttpStatusCode.BadRequest,
                InvalidBodyResponse(
                    "invalid_body",
                    when (result.problem) {
                      BodyProblem.NOT_A_JSON_OBJECT -> "not_a_json_object"
                      BodyProblem.TOO_LARGE -> "too_large"
                    },
                    when (result.problem) {
                      BodyProblem.NOT_A_JSON_OBJECT -> "請求本文需為 JSON 物件，鍵為參數名稱、值為文字。"
                      BodyProblem.TOO_LARGE -> "請求本文超過大小上限。"
                    },
                ),
            )
        is WebhookResult.InvalidParameters ->
            call.respond(
                HttpStatusCode.BadRequest,
                ParameterErrorResponse(
                    "invalid_caller_parameters",
                    "請求本文的參數不符：" +
                        result.problems.joinToString("；") {
                          when (it.kind) {
                            CallerProblemKind.MISSING -> "缺少必填參數 ${it.name}"
                            CallerProblemKind.UNDECLARED -> "不接受的參數 ${it.name}"
                            CallerProblemKind.NOT_TEXT -> "參數 ${it.name} 的值需為文字"
                            CallerProblemKind.NUL_IN_VALUE -> "參數 ${it.name} 的值含有 NUL 字元"
                          }
                        },
                    result.problems.map {
                      ParameterProblemDoc(
                          it.name,
                          when (it.kind) {
                            CallerProblemKind.MISSING -> "missing"
                            CallerProblemKind.UNDECLARED -> "undeclared"
                            CallerProblemKind.NOT_TEXT -> "not_text"
                            CallerProblemKind.NUL_IN_VALUE -> "contains_nul"
                          },
                      )
                    },
                ),
            )
        WebhookResult.Failed ->
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("internal_error", "暫時無法處理這次呼叫；請以相同的 delivery 識別碼重送。"),
            )
      }
    }
  }
}
