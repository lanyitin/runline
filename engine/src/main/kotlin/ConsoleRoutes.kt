package dev.lawlan.runline.engine

import dev.lawlan.runline.engine.console.ConsoleAnswer
import dev.lawlan.runline.engine.console.ConsoleAssets
import dev.lawlan.runline.engine.console.ConsoleContent
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * What the browser may do with the Console (ADR-015, ADR-017): everything from this origin and
 * nothing from anywhere else, no script or style written into a page, no plug-ins, no embedding in
 * a frame. A script that got into a page by some other way still cannot be loaded from, or send the
 * token to, another place.
 */
private val CONSOLE_SECURITY_HEADERS =
    mapOf(
        "Content-Security-Policy" to
            listOf(
                    "default-src 'self'",
                    "script-src 'self'",
                    "style-src 'self'",
                    "font-src 'self'",
                    "img-src 'self' data:",
                    "connect-src 'self'",
                    "object-src 'none'",
                    "base-uri 'none'",
                    "form-action 'self'",
                    "frame-ancestors 'none'",
                )
                .joinToString("; "),
        "X-Content-Type-Options" to "nosniff",
        "X-Frame-Options" to "DENY",
        "Referrer-Policy" to "no-referrer",
    )

/**
 * The Console (WI-31, ADR-015): its files at `/`, and the entry page for the paths of the single
 * page application. One route takes every `GET` that no other route does, so what the API registers
 * always goes first; [ConsoleContent] keeps `/api` and `/openapi` out of it.
 *
 * The files are asked of dependency injection when a request arrives, not when the application
 * starts: nothing in them can fail the start, and a test can give files of its own.
 */
fun Application.configureConsoleRoutes() {
  val dependencies = dependencies

  routing {
    get("{path...}") {
      val content = ConsoleContent(dependencies.resolve<ConsoleAssets>())
      when (val answer = content.answer(call.parameters.getAll("path").orEmpty())) {
        is ConsoleAnswer.NotFound -> call.respond(HttpStatusCode.NotFound)
        is ConsoleAnswer.Content -> {
          CONSOLE_SECURITY_HEADERS.forEach { (name, value) -> call.response.header(name, value) }
          call.response.header(HttpHeaders.CacheControl, answer.cacheControl)
          call.respondBytes(answer.asset.bytes, answer.asset.contentType)
        }
      }
    }
  }
}
