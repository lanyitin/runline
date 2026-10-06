package dev.lawlan.runline.engine

import dev.lawlan.runline.engine.allowlist.*
import dev.lawlan.runline.engine.artifact.ErrorResponse
import dev.lawlan.runline.engine.auth.Role
import dev.lawlan.runline.engine.auth.authorized
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
 * Allow list administration (WI-10). Administrators only: a developer, even to look, is answered
 * 403 (ADR-012). Routes only convert HTTP to and from [AllowListAdmin]; the changer is recorded as
 * the name of the caller's token. A kind in a path or body is `package` or `class`.
 *
 * - `GET /api/v1/allowlist`: the list in force with its version.
 * - `GET /api/v1/allowlist/versions`: the history, newest first (`limit`, default 50, up to 200).
 * - `POST /api/v1/allowlist/entries`: body `{kind, name, exactOnly?}`. 201 with the change.
 * - `GET|PATCH|DELETE /api/v1/allowlist/entries/{kind}/{name}`.
 * - `POST /api/v1/allowlist/recheck`: judge every stored definition again.
 *
 * Every operation that changes something takes `?preview=true`: the answer then says what would
 * flip, with 200, and nothing is changed. The change and the judging again it triggers are one
 * database transaction, so the request lasts as long as the judging does.
 */
fun Application.configureAllowListRoutes() {
  val admin: AllowListAdmin by dependencies

  routing {
    route("/api/v1/allowlist") {
      authorized(Role.ADMIN) {
        get { call.respond(withContext(Dispatchers.IO) { admin.current() }.toResponse()) }

        get("/versions") {
          val limit =
              call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, MAX_VERSIONS)
          call.respond(
              VersionListResponse(
                  withContext(Dispatchers.IO) { admin.versions(limit ?: DEFAULT_VERSIONS) }
                      .map { it.toResponse() }
              )
          )
        }

        post("/entries") {
          val preview = call.previewOrRespond() ?: return@post
          val request =
              try {
                call.receive<AddEntryRequest>()
              } catch (e: CancellationException) {
                throw e
              } catch (e: Exception) {
                return@post call.respondBadRequest(
                    "請求內容需為 JSON：kind（package 或 class）、name，以及選填的 exactOnly。"
                )
              }
          val kind =
              parseKind(request.kind)
                  ?: return@post call.respondBadRequest("kind 必須是 package 或 class。")
          call.respondChange(
              admin,
              AllowListChange.Add(kind, request.name, request.exactOnly),
              preview,
              created = true,
          )
        }

        route("/entries/{kind}/{name}") {
          get {
            val (kind, name) = call.entryKey() ?: return@get
            val entry = withContext(Dispatchers.IO) { admin.find(kind, name) }
            if (entry == null) call.respondEntryNotFound() else call.respond(entry.toResponse())
          }

          patch {
            val preview = call.previewOrRespond() ?: return@patch
            val (kind, name) = call.entryKey() ?: return@patch
            val request =
                try {
                  call.receive<ModifyEntryRequest>()
                } catch (e: CancellationException) {
                  throw e
                } catch (e: Exception) {
                  return@patch call.respondBadRequest("請求內容需為 JSON：選填的 name 與 exactOnly。")
                }
            call.respondChange(
                admin,
                AllowListChange.Modify(kind, name, request.name, request.exactOnly),
                preview,
            )
          }

          delete {
            val preview = call.previewOrRespond() ?: return@delete
            val (kind, name) = call.entryKey() ?: return@delete
            call.respondChange(admin, AllowListChange.Remove(kind, name), preview)
          }
        }

        post("/recheck") {
          val preview = call.previewOrRespond() ?: return@post
          call.respondChange(admin, AllowListChange.Recheck, preview)
        }
      }
    }
  }
}

private const val DEFAULT_VERSIONS = 50
private const val MAX_VERSIONS = 200

private fun parseKind(text: String): EntryKind? =
    EntryKind.entries.firstOrNull { it.wireName == text }

/** The kind and name of the entry in the path, or null after answering 400. */
private suspend fun ApplicationCall.entryKey(): Pair<EntryKind, String>? {
  val kind = parseKind(parameters["kind"].orEmpty())
  if (kind == null) {
    respondBadRequest("路徑中的 kind 必須是 package 或 class。")
    return null
  }
  return kind to parameters["name"].orEmpty()
}

/** Whether `?preview=true` was given; null after answering 400 for any other value. */
private suspend fun ApplicationCall.previewOrRespond(): ChangeMode? =
    when (request.queryParameters["preview"]) {
      null,
      "false" -> ChangeMode.APPLY
      "true" -> ChangeMode.PREVIEW
      else -> {
        respondBadRequest("preview 只能是 true 或 false。")
        null
      }
    }

private suspend fun ApplicationCall.respondBadRequest(message: String) =
    respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", message))

private suspend fun ApplicationCall.respondEntryNotFound() =
    respond(HttpStatusCode.NotFound, ErrorResponse("entry_not_found", "找不到這個白名單條目。"))

private suspend fun ApplicationCall.respondChange(
    admin: AllowListAdmin,
    change: AllowListChange,
    mode: ChangeMode,
    created: Boolean = false,
) {
  val result = withContext(Dispatchers.IO) { admin.change(change, caller, mode) }
  when (result) {
    is ChangeResult.Applied -> {
      val entry = result.entry
      if (created && entry != null) {
        response.header(
            HttpHeaders.Location,
            "/api/v1/allowlist/entries/${entry.kind.wireName}/${entry.name}",
        )
      }
      respond(
          if (created) HttpStatusCode.Created else HttpStatusCode.OK,
          ChangeResponse(
              preview = false,
              version =
                  (result.version?.version
                          ?: withContext(Dispatchers.IO) { admin.current().number })
                      .toString(),
              entry = entry?.toResponse(),
              impact = result.impact.toResponse(),
              redundantEntries = result.redundant.map { it.toResponse() },
          ),
      )
    }
    is ChangeResult.Previewed ->
        respond(
            ChangeResponse(
                preview = true,
                version = result.currentVersion.toString(),
                entry = null,
                impact = result.impact.toResponse(),
                redundantEntries = result.redundant.map { it.toResponse() },
            )
        )
    is ChangeResult.Invalid ->
        respond(
            HttpStatusCode.UnprocessableEntity,
            EntryRefusal(
                "invalid_entry",
                result.message,
                problem = result.problem.name.lowercase(),
            ),
        )
    is ChangeResult.Duplicate ->
        respond(
            HttpStatusCode.Conflict,
            EntryRefusal(
                "entry_exists",
                "已經有同種類、同名稱的條目；要改變它請用 PATCH。",
                existing = result.existing.toResponse(),
            ),
        )
    is ChangeResult.Covered ->
        respond(
            HttpStatusCode.Conflict,
            EntryRefusal(
                "entry_covered",
                "現有的條目「${result.by.name}」（${result.by.kind.wireName}）已經涵蓋它，不需要再加。",
                coveredBy = result.by.toResponse(),
            ),
        )
    ChangeResult.NotFound -> respondEntryNotFound()
  }
}
