package dev.lawlan.runline.engine

import dev.lawlan.runline.engine.artifact.*
import dev.lawlan.runline.engine.auth.Role
import dev.lawlan.runline.engine.auth.authorized
import dev.lawlan.runline.engine.config.EngineConfig
import dev.lawlan.runline.engine.resource.ResourceWarnings
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.util.getOrFail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Upload and query API (WI-06). Routes only convert HTTP to and from the services; every rule lives
 * in [UploadService] and [ArtifactCatalog].
 *
 * - `POST /api/v1/artifacts` (developer): body is the raw jar. 201 new version (also when others
 *   uploaded the same content), 200 the uploader's own earlier version, 422 rejected, 413 too
 *   large.
 * - `GET /api/v1/artifacts/{contentHash}[?uploader=]` (developer): own artifacts; administrators
 *   any, naming the uploader when several uploaded the content (409 `ambiguous_version`).
 * - `GET /api/v1/definitions` (developer): own definitions; administrators all.
 * - `GET /api/v1/artifacts/{contentHash}/deletion-impact[?uploader=]` (administrator): what
 *   deleting the version would take along or be stopped by; changes nothing (ADR-047).
 * - `DELETE /api/v1/artifacts/{contentHash}[?uploader=][&runs=]` (administrator): 204, 404, 400,
 *   409 `in_use` (triggers), `runs_active`, `runs_exist`, `impact_changed`, or when the version
 *   must be named `ambiguous_version`. `runs` confirms deleting that many ended runs with the
 *   version.
 */
fun Application.configureArtifactRoutes() {
  val uploads: UploadService by dependencies
  val catalog: ArtifactCatalog by dependencies
  val staging: UploadStaging by dependencies
  val config: EngineConfig by dependencies
  val warnings: ResourceWarnings by dependencies

  routing {
    route("/api/v1") {
      authorized(Role.DEVELOPER) {
        post("/artifacts") {
          val caller = call.caller
          val result =
              try {
                withContext(Dispatchers.IO) {
                  staging.stage(call.receiveStream(), config.upload.maxBytes).use {
                    uploads.upload(it, caller.name)
                  }
                }
              } catch (e: UploadTooLargeException) {
                return@post call.respond(
                    HttpStatusCode.PayloadTooLarge,
                    ErrorResponse("too_large", "上傳的檔案超過上限 ${e.maxBytes} bytes。"),
                )
              }
          when (result) {
            is UploadResult.Created ->
                call.respond(
                    HttpStatusCode.Created,
                    withContext(Dispatchers.IO) { result.artifact.respondWith(warnings) },
                )
            is UploadResult.Existing ->
                call.respond(
                    HttpStatusCode.OK,
                    withContext(Dispatchers.IO) { result.artifact.respondWith(warnings) },
                )
            is UploadResult.Rejected ->
                call.respond(
                    HttpStatusCode.UnprocessableEntity,
                    ErrorResponse(result.reason.name.lowercase(), result.message),
                )
          }
        }
        get("/artifacts/{contentHash}") {
          val hash = call.parameters.getOrFail("contentHash")
          val uploader = call.request.queryParameters["uploader"]
          val found = withContext(Dispatchers.IO) { catalog.find(hash, uploader, call.caller) }
          when (found) {
            is VersionOutcome.Resolved ->
                call.respond(withContext(Dispatchers.IO) { found.value.respondWith(warnings) })
            is VersionOutcome.Ambiguous -> call.respondAmbiguousVersion(found)
            VersionOutcome.NotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", "找不到這個版本。"))
          }
        }
        get("/definitions") {
          val artifacts = withContext(Dispatchers.IO) { catalog.list(call.caller) }
          call.respond(
              withContext(Dispatchers.IO) { artifacts.toDefinitionList(warnings.lookup(artifacts)) }
          )
        }
      }
      authorized(Role.ADMIN) {
        get("/artifacts/{contentHash}/deletion-impact") {
          val hash = call.parameters.getOrFail("contentHash")
          val uploader = call.request.queryParameters["uploader"]
          val impact =
              withContext(Dispatchers.IO) { catalog.deletionImpact(hash, uploader, call.caller) }
          when (impact) {
            is VersionOutcome.Resolved -> call.respond(impact.value.toResponse())
            is VersionOutcome.Ambiguous -> call.respondAmbiguousVersion(impact)
            VersionOutcome.NotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", "找不到這個版本。"))
          }
        }
        delete("/artifacts/{contentHash}") {
          val hash = call.parameters.getOrFail("contentHash")
          val uploader = call.request.queryParameters["uploader"]
          val confirmed =
              call.request.queryParameters["runs"]?.let { raw ->
                raw.toLongOrNull()?.takeIf { it >= 0 }
                    ?: return@delete call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("bad_request", "runs 必須是不小於 0 的整數。"),
                    )
              }
          val outcome =
              withContext(Dispatchers.IO) { catalog.delete(hash, uploader, call.caller, confirmed) }
          if (outcome is VersionOutcome.Ambiguous)
              return@delete call.respondAmbiguousVersion(outcome)
          when (val result = (outcome as? VersionOutcome.Resolved)?.value) {
            is DeleteResult.Deleted -> call.respond(HttpStatusCode.NoContent)
            null,
            DeleteResult.NotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", "找不到這個版本。"))
            is DeleteResult.InUse ->
                call.respond(
                    HttpStatusCode.Conflict,
                    InUseResponse(
                        "in_use",
                        "這個版本仍被 trigger 引用，不能刪除；請先改綁或刪除這些 trigger。",
                        result.triggers.map { TriggerReferenceDoc(it.name, it.kind) },
                    ),
                )
            is DeleteResult.RunsActive ->
                call.respond(
                    HttpStatusCode.Conflict,
                    RunsActiveResponse(
                        "runs_active",
                        "這個版本有 ${result.active} 個尚未結束的 run，不能刪除；請先取消或等待結束。",
                        result.active,
                    ),
                )
            is DeleteResult.RunsExist ->
                call.respond(
                    HttpStatusCode.Conflict,
                    RunsTotalResponse(
                        "runs_exist",
                        "這個版本有 ${result.total} 個 run；請先預覽影響，並以 runs=${result.total} 確認一併刪除。",
                        result.total,
                    ),
                )
            is DeleteResult.ImpactChanged ->
                call.respond(
                    HttpStatusCode.Conflict,
                    RunsTotalResponse(
                        "impact_changed",
                        "這個版本現在有 ${result.total} 個 run，與確認的數量不符；請重新預覽。",
                        result.total,
                    ),
                )
          }
        }
      }
    }
  }
}

/** The artifact as the API shows it, with the warnings about the resources it declares. */
private fun ArtifactRecord.respondWith(warnings: ResourceWarnings) =
    toResponse(warnings.lookup(listOf(this)))

/**
 * 409 `ambiguous_version`: the caller can see several versions of the content and must say whose
 * they mean with `uploader` (ADR-020). Only administrators can see several, so only they get this.
 */
suspend fun ApplicationCall.respondAmbiguousVersion(ambiguous: VersionOutcome.Ambiguous) =
    respond(
        HttpStatusCode.Conflict,
        AmbiguousVersionResponse(
            "ambiguous_version",
            "這個內容有多位上傳者的版本（${ambiguous.uploaders.joinToString("、")}），請以 uploader 指定要用哪一個。",
            ambiguous.uploaders,
        ),
    )
