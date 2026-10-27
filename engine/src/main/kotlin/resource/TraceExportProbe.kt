package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.accessors.tls.ResourceTls
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import javax.net.ssl.SSLException

/**
 * Looks at the endpoint of a `trace-export` resource the way an administrator's check does (ADR-019
 * decision 8): one OTLP export request with no span in it, which a receiver takes as the empty
 * export it is, sent with the resource's credential and certificates and the check's own limit on
 * time. Nothing is exported and nothing of the answer is kept but its category.
 */
internal object TraceExportProbe {
  fun check(
      endpoint: URI,
      credential: String,
      timeout: Duration,
      tls: ResourceTls?,
  ): CheckFailure? {
    val context = tls?.newContext()
    val client =
        HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .also { builder -> context?.let { builder.sslContext(it.sslContext) } }
            .build()
    val request =
        HttpRequest.newBuilder(endpoint)
            .timeout(timeout)
            .header("Content-Type", "application/x-protobuf")
            .header("Authorization", credential)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()
    return try {
      val status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
      when (status) {
        in 200..299 -> null
        401,
        403 -> CheckFailure.REJECTED
        in 300..399 -> CheckFailure.REDIRECT_BLOCKED
        in 500..599 -> CheckFailure.SERVER_ERROR
        else -> CheckFailure.UNEXPECTED_RESPONSE
      }
    } catch (e: HttpTimeoutException) {
      // Also the timeout of connecting, which is a subtype.
      CheckFailure.TIMEOUT
    } catch (e: IOException) {
      context?.classify(e)?.let { CheckFailure.fromWire(it.wire) }
          ?: if (e is SSLException) CheckFailure.HANDSHAKE_FAILED
          else CheckFailure.CONNECTION_FAILED
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      CheckFailure.TIMEOUT
    } finally {
      client.close()
    }
  }
}
