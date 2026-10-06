package dev.lawlan.runline.engine

import dev.lawlan.runline.engine.auth.Role
import dev.lawlan.runline.engine.auth.authorized
import dev.lawlan.runline.engine.info.BuildInfo
import dev.lawlan.runline.engine.info.InfoResponse
import dev.lawlan.runline.engine.info.SystemStatus
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the Engine says about itself (WI-28, ADR-016).
 *
 * - `GET /api/v1/info`: open to everyone, the version, the commit hash and whether the tree was
 *   dirty, and nothing else. It reads what the build wrote into the jar and asks nothing of the
 *   database or any other backing service, so it answers when they do not.
 * - `GET /api/v1/system`: a developer's (or administrator's) token; the details, and who the token
 *   belongs to.
 *
 * Neither answer is cached.
 */
fun Application.configureInfoRoutes() {
  // Resolved now, so a jar that was not built correctly fails the start, and "started at" is the
  // start rather than the first request.
  val buildInfo: BuildInfo by dependencies
  val systemStatus: SystemStatus by dependencies
  val build = buildInfo
  val status = systemStatus
  val info = InfoResponse(build.version, build.commitHash, build.dirty)

  routing {
    route("/api/v1") {
      get("/info") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respond(info)
      }

      authorized(Role.DEVELOPER) {
        get("/system") {
          call.response.header(HttpHeaders.CacheControl, "no-store")
          call.respond(withContext(Dispatchers.IO) { status.report(call.caller) })
        }
      }
    }
  }
}
