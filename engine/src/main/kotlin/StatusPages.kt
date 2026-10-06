package dev.lawlan.runline.engine

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import java.util.UUID
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/** The answer to a failure nobody expected: no cause, only a way to find it in the log. */
@Serializable
data class InternalErrorResponse(val error: String, val message: String, val errorId: String)

private val log = LoggerFactory.getLogger("dev.lawlan.runline.engine.UnexpectedError")

/**
 * Records an unexpected failure with everything about it in the log, under a new identifier, and
 * returns that identifier. The identifier is all a caller is told, so that what they report can be
 * found in the log without the response revealing the cause (stack, SQL, configuration).
 */
fun logUnexpectedError(what: String, cause: Throwable): String {
  val errorId = UUID.randomUUID().toString()
  log.error("Unexpected error {} while {}", errorId, what, cause)
  return errorId
}

fun internalErrorMessage(errorId: String) = "伺服器發生未預期的錯誤。請提供錯誤識別碼 $errorId 以便查找。"

fun Application.configureStatusPages() {
  install(StatusPages) {
    exception<Throwable> { call, cause ->
      val errorId =
          logUnexpectedError("handling a ${call.request.local.method.value} request", cause)
      call.respond(
          HttpStatusCode.InternalServerError,
          InternalErrorResponse("internal_error", internalErrorMessage(errorId), errorId),
      )
    }
  }
}
