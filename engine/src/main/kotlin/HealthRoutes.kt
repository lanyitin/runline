package dev.lawlan.runline.engine

import dev.lawlan.runline.engine.health.Readiness
import dev.lawlan.runline.engine.health.ReadinessStatus
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable data class LiveResponse(val status: String)

@Serializable data class ReadyResponse(val status: String)

@Serializable data class NotReadyResponse(val status: String, val checks: Map<String, String>)

/**
 * The probes of the deployment platform (WI-29, ADR-018): open to everyone, never cached, and
 * without a cause, a version or a setting in what they answer.
 *
 * - `GET /api/v1/health/live`: the process answers HTTP. It asks nothing of the database or of
 *   runs.
 * - `GET /api/v1/health/ready`: every check is in order, else 503 with what each check found.
 */
fun Application.configureHealthRoutes() {
  val readiness: Readiness by dependencies

  routing {
    route("/api/v1/health") {
      get("/live") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respond(LiveResponse("up"))
      }
      get("/ready") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        val report = withContext(Dispatchers.IO) { readiness.evaluate() }
        if (report.status == ReadinessStatus.READY) {
          call.respond(ReadyResponse("ready"))
        } else {
          call.respond(
              HttpStatusCode.ServiceUnavailable,
              NotReadyResponse(report.status.text, report.checks.mapValues { it.value.text }),
          )
        }
      }
    }
  }
}
