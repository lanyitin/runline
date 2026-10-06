package dev.lawlan.runline.engine.support

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * An HTTP client for tests with a limit on connecting and on every request, so that a server that
 * does not answer fails the test with a message instead of holding it for ever.
 *
 * The limit covers the whole exchange to the last byte of the answer: the JDK's own request timeout
 * ends when the response headers arrive, which would leave an answer that stops half way waiting
 * without end.
 */
class TimedHttp(
    private val requestTimeout: Duration = TestTimeouts.httpRequest,
    connectTimeout: Duration = TestTimeouts.httpConnect,
) {
  val client: HttpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build()

  fun get(url: String, token: String? = null): HttpResponse<String> =
      send(
          HttpRequest.newBuilder(URI(url)).also { b ->
            token?.let { b.header("Authorization", "Bearer $it") }
          }
      )

  /** Sends [request], which has its method and body set but no timeout of its own. */
  fun send(request: HttpRequest.Builder): HttpResponse<String> {
    val built = request.timeout(requestTimeout).build()
    val pending = client.sendAsync(built, HttpResponse.BodyHandlers.ofString())
    try {
      return pending.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
      pending.cancel(true)
      throw unanswered(built, e)
    } catch (e: ExecutionException) {
      if (e.cause is java.net.http.HttpTimeoutException) throw unanswered(built, e.cause!!)
      throw (e.cause as? java.io.IOException ?: e)
    }
  }

  private fun unanswered(request: HttpRequest, cause: Throwable) =
      AssertionError(
          "no answer to ${request.method()} ${request.uri()} within ${requestTimeout.toMillis()} ms",
          cause,
      )
}
