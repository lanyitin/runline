package dev.lawlan.runline.engine.traceexport

import io.opentelemetry.common.ComponentLoader
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.common.export.HttpResponse
import io.opentelemetry.sdk.common.export.HttpSender
import io.opentelemetry.sdk.common.export.HttpSenderConfig
import io.opentelemetry.sdk.common.export.HttpSenderProvider
import io.opentelemetry.sdk.common.export.MessageWriter
import java.io.ByteArrayOutputStream
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse as JdkResponse
import java.util.ServiceLoader
import java.util.function.Consumer

/**
 * The HTTP sender of the exporters of `trace-export` resources. OpenTelemetry's own sender (OkHttp)
 * follows redirects and has no setting to stop it, so a redirect would carry the credential to an
 * address the administrator never gave (ADR-46 decision 8: pipelines and endpoints are the
 * administrator's). This one never follows a redirect: the answer 3xx is the answer, and the
 * exporter counts it as a failed export. It uses the JDK's client, which checks the host of an
 * https endpoint itself, with the security context of the resource.
 */
internal class NoRedirectHttpSender(private val config: HttpSenderConfig) : HttpSender {
  private val client: HttpClient =
      HttpClient.newBuilder()
          .followRedirects(HttpClient.Redirect.NEVER)
          .connectTimeout(config.connectTimeout)
          .also { builder -> config.sslContext?.let { builder.sslContext(it) } }
          .build()

  override fun send(
      writer: MessageWriter,
      onResponse: Consumer<HttpResponse>,
      onError: Consumer<Throwable>,
  ) {
    try {
      val body = ByteArrayOutputStream(writer.contentLength.coerceAtLeast(0))
      writer.writeMessage(body)
      val request =
          HttpRequest.newBuilder(config.endpoint)
              .timeout(config.timeout)
              .header("Content-Type", config.contentType)
              .also { builder ->
                config.headersSupplier.get().forEach { (name, values) ->
                  values.forEach { builder.header(name, it) }
                }
              }
              .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
              .build()
      client.sendAsync(request, JdkResponse.BodyHandlers.discarding()).whenComplete { r, error ->
        if (error != null) onError.accept(error) else onResponse.accept(Answer(r.statusCode()))
      }
    } catch (e: Exception) {
      onError.accept(e)
    }
  }

  override fun shutdown(): CompletableResultCode {
    client.close()
    return CompletableResultCode.ofSuccess()
  }

  /** Only the status counts; the body of an answer is not kept. */
  private class Answer(private val status: Int) : HttpResponse {
    override fun getStatusCode(): Int = status

    override fun getStatusMessage(): String = ""

    override fun getResponseBody(): ByteArray = ByteArray(0)
  }

  /** Gives the exporter this sender and, for anything else it loads, what it would have found. */
  object Components : ComponentLoader {
    private val provider = HttpSenderProvider { NoRedirectHttpSender(it) }

    @Suppress("UNCHECKED_CAST")
    override fun <T> load(spi: Class<T>): Iterable<T> =
        if (spi == HttpSenderProvider::class.java) listOf(provider as T)
        else ServiceLoader.load(spi, NoRedirectHttpSender::class.java.classLoader)
  }
}
