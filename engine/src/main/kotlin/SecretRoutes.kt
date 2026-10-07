package dev.lawlan.runline.engine

import dev.lawlan.runline.engine.artifact.ErrorResponse
import dev.lawlan.runline.engine.auth.Role
import dev.lawlan.runline.engine.auth.authorized
import dev.lawlan.runline.engine.secret.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The secrets of the keystore (WI-41, ADR-019 decision 6). Administrators only (ADR-012). The
 * values of secrets are in no answer, and no request carries one: the API knows aliases.
 *
 * - `GET /api/v1/secrets`: `{secrets: [{alias, type, status, usedBy}]}`; 409
 *   `secret_store_not_configured` when the Engine has no keystore.
 * - `POST /api/v1/secrets/reload`: reads the keystore again as a whole. 200 `{aliases, changed:
 *   [{alias, usedBy}]}`; 409 `secret_store_not_configured`; 422 `secret_store_unreadable` with the
 *   category as `problem`, and what was in memory stays.
 */
fun Application.configureSecretRoutes() {
  val catalog: SecretCatalog by dependencies

  routing {
    route("/api/v1/secrets") {
      authorized(Role.ADMIN) {
        get {
          val listed = withContext(Dispatchers.IO) { catalog.list() }
          if (listed == null) call.respondNotConfigured()
          else call.respond(SecretListResponse(listed.map { it.toDoc() }))
        }

        post("/reload") {
          when (val outcome = withContext(Dispatchers.IO) { catalog.reload(call.caller) }) {
            is SecretReloadOutcome.Reloaded -> call.respond(outcome.toDoc())
            SecretReloadOutcome.NotConfigured -> call.respondNotConfigured()
            is SecretReloadOutcome.Failed ->
                call.respond(
                    HttpStatusCode.UnprocessableEntity,
                    SecretStoreUnreadableResponse(
                        "secret_store_unreadable",
                        "金鑰庫無法讀取（${outcome.failure.wire}），記憶體中的機密維持原狀。",
                        outcome.failure.wire,
                    ),
                )
          }
        }
      }
    }
  }
}

private suspend fun ApplicationCall.respondNotConfigured() =
    respond(
        HttpStatusCode.Conflict,
        ErrorResponse("secret_store_not_configured", "Engine 沒有組態金鑰庫。"),
    )
