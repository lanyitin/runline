package dev.lawlan.runline.engine

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.system.exitProcess

private const val DEFAULT_PORT = "8080"
private val PROBES = setOf("live", "ready")

/** Under the 3 second limit a platform gives a probe (ADR-018); the whole request is bounded. */
private val CHECK_TIMEOUT = Duration.ofSeconds(2)

/**
 * The address of a probe on this machine: the Engine listens on `PORT` (default 8080), the same
 * variable it reads, so the check needs no other configuration.
 */
internal fun probeUri(probe: String, env: Map<String, String>): URI =
    URI(
        "http://localhost:${env["PORT"]?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_PORT}/api/v1/health/$probe"
    )

/**
 * The container health check, for an image that holds only a JDK: asks this machine's Engine
 * whether it is `live` or `ready` (ADR-018) and answers with the exit code, so no HTTP client is
 * needed in the image. `java -cp engine.jar dev.lawlan.runline.engine.HealthCheckKt ready`. Exit 0:
 * the probe answered 200; 1: it did not answer in time or did not say 200; 2: the argument is not
 * `live` or `ready`.
 */
fun main(args: Array<String>) {
  val probe = args.singleOrNull()?.takeIf { it in PROBES }
  if (probe == null) {
    System.err.println("Usage: HealthCheckKt live|ready")
    exitProcess(2)
  }
  val healthy =
      try {
        val client = HttpClient.newBuilder().connectTimeout(CHECK_TIMEOUT).build()
        val request =
            HttpRequest.newBuilder(probeUri(probe, System.getenv())).timeout(CHECK_TIMEOUT).build()
        client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200
      } catch (e: Exception) {
        System.err.println("The $probe probe did not answer: ${e.javaClass.simpleName}")
        false
      }
  if (!healthy) exitProcess(1)
  exitProcess(0)
}
