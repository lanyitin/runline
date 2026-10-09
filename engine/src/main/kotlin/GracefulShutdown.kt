package dev.lawlan.runline.engine

import dev.lawlan.runline.engine.artifact.ErrorResponse
import dev.lawlan.runline.engine.health.EngineLifecycle
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.lawlan.runline.engine.GracefulShutdown")

/** The paths the platform probes; the one exception to turning requests away while stopping. */
private val PROBE_PATHS = setOf("/api/v1/health/live", "/api/v1/health/ready")

/**
 * When the Engine is told to stop, it stops admitting requests (answered 503 `shutting_down`, with
 * the connection closed), begins the one budget of the shutdown ([ShutdownBudget]) and waits, at
 * most for what is left of it, for those in flight to finish; only then does the server shut its
 * connections. A WebSocket session is not a request in flight: it is open for as long as its run,
 * so waiting for it would make every shutdown last the grace time.
 */
fun Application.configureGracefulShutdown() {
  val lifecycle: EngineLifecycle by dependencies
  val shutdown: ShutdownBudget by dependencies
  val requests = InFlightRequests()

  intercept(ApplicationCallPipeline.Setup) {
    // The probes are how the platform learns that the Engine is stopping; they are answered to the
    // end (ADR-018), neither counted nor turned away.
    if (call.request.path() in PROBE_PATHS) return@intercept
    val session = call.request.headers[HttpHeaders.Upgrade].equals("websocket", ignoreCase = true)
    if (!requests.tryBegin(counted = !session)) {
      call.response.header(HttpHeaders.Connection, "close")
      call.respond(
          HttpStatusCode.ServiceUnavailable,
          ErrorResponse("shutting_down", "Engine 正在關閉，不接受新的請求。請稍後重試。"),
      )
      finish()
      return@intercept
    }
    try {
      proceed()
    } finally {
      if (!session) requests.end()
    }
  }

  monitor.subscribe(ApplicationStopPreparing) {
    // First of all: the platform stops sending traffic before the wait for requests begins.
    lifecycle.beginShutdown()
    shutdown.start()
    val left = shutdown.remaining()
    log.info(
        "Shutting down: no new requests are admitted; waiting up to {} for those in flight",
        left,
    )
    if (requests.drain(left)) {
      log.info("Shutting down: no request in flight")
    } else {
      log.warn("Shutting down: requests still in flight after {} are cut off", left)
    }
  }
}
