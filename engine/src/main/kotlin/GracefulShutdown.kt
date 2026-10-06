package dev.lawlan.runline.engine

import dev.lawlan.runline.engine.artifact.ErrorResponse
import dev.lawlan.runline.engine.config.EngineConfig
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.lawlan.runline.engine.GracefulShutdown")

/**
 * When the Engine is told to stop, it stops admitting requests (answered 503 `shutting_down`, with
 * the connection closed) and waits, at most the grace time, for those in flight to finish; only
 * then does the server shut its connections. A WebSocket session is not a request in flight: it is
 * open for as long as its run, so waiting for it would make every shutdown last the grace time.
 */
fun Application.configureGracefulShutdown() {
  val config: EngineConfig by dependencies
  val requests = InFlightRequests()

  intercept(ApplicationCallPipeline.Setup) {
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
    val grace = config.runs.shutdownGrace
    log.info(
        "Shutting down: no new requests are admitted; waiting up to {} for those in flight",
        grace,
    )
    if (requests.drain(grace)) {
      log.info("Shutting down: no request in flight")
    } else {
      log.warn("Shutting down: requests still in flight after {} are cut off", grace)
    }
  }
}
