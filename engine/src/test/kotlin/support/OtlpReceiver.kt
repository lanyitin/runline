package dev.lawlan.runline.engine.support

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import javax.net.ssl.SSLContext

/** A span as an OTLP receiver read it from the wire (OTLP over HTTP, protobuf). */
data class ReceivedSpan(
    val traceId: String,
    val spanId: String,
    /** Empty for a root span. */
    val parentSpanId: String,
    val name: String,
    val startNanos: Long,
    val endNanos: Long,
    val attributes: Map<String, Any?>,
    /** 0 unset, 1 ok, 2 error. */
    val statusCode: Int,
    val statusMessage: String,
    /** The `service.name` of the resource the span came with. */
    val serviceName: String?,
)

/** One export request the receiver took in. */
class ReceivedRequest(
    val path: String,
    /** Header names in lower case. */
    val headers: Map<String, String>,
    val body: ByteArray,
    val spans: List<ReceivedSpan>,
    /** The names of the metrics of a metrics request (empty for any other request). */
    val metricNames: List<String> = emptyList(),
) {
  val authorization: String?
    get() = headers["authorization"]
}

/**
 * A small real OTLP/HTTP trace receiver on a real socket: it reads the protobuf of an
 * `ExportTraceServiceRequest` the way a collector does, keeps what it received and answers like
 * one. Tests choose how it answers: [answer] gives the status of each request, and [hang] makes it
 * take requests and not answer until it is closed (a receiver whose connection hangs).
 */
class OtlpReceiver(tls: SSLContext? = null) : AutoCloseable {
  private val server: HttpServer =
      if (tls == null) {
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
      } else {
        // The name the certificate is for, so that the client checks the host it connects to.
        HttpsServer.create(InetSocketAddress(InetAddress.getByName("localhost"), 0), 0).also {
          it.httpsConfigurator = HttpsConfigurator(tls)
        }
      }
  private val scheme = if (tls == null) "http" else "https"
  private val host = if (tls == null) "127.0.0.1" else "localhost"
  private val released = CountDownLatch(1)
  private val pool: ExecutorService = Executors.newCachedThreadPool {
    Thread(it, "otlp-receiver").apply { isDaemon = true }
  }

  /** Every request taken in, in order of arrival; a hung one is listed when it arrives. */
  val requests = CopyOnWriteArrayList<ReceivedRequest>()

  /** The status a request is answered with (200 is a success of the export). */
  @Volatile var answer: (ReceivedRequest) -> Int = { 200 }

  /** The `Location` header of every answer, for a receiver that redirects. */
  @Volatile var location: String? = null

  /** While true, requests are taken in and not answered. */
  @Volatile var hang = false

  val endpoint: String
    get() = "$baseUrl/v1/traces"

  /** What `OTEL_EXPORTER_OTLP_ENDPOINT` is given: the signals' paths are added to it. */
  val baseUrl: String
    get() = "$scheme://$host:${server.address.port}"

  /** The names of all metrics received so far. */
  val metricNames: List<String>
    get() = requests.flatMap { it.metricNames }

  /** All spans received so far. */
  val spans: List<ReceivedSpan>
    get() = requests.flatMap { it.spans }

  init {
    server.executor = pool
    server.createContext("/") { exchange -> handle(exchange) }
    server.start()
  }

  private fun handle(exchange: HttpExchange) {
    exchange.use {
      val raw = it.requestBody.readAllBytes()
      val body =
          if (it.requestHeaders.getFirst("Content-Encoding") == "gzip")
              GZIPInputStream(ByteArrayInputStream(raw)).readAllBytes()
          else raw
      val headers = it.requestHeaders.entries.associate { (k, v) -> k.lowercase() to v.first() }
      val path = it.requestURI.path
      val received =
          if (path.endsWith("/v1/metrics"))
              ReceivedRequest(path, headers, body, emptyList(), ProtoReader.metricNamesOf(body))
          else ReceivedRequest(path, headers, body, ProtoReader.spansOf(body))
      requests += received
      if (hang) released.await()
      location?.let { target -> it.responseHeaders.add("Location", target) }
      it.sendResponseHeaders(answer(received), -1)
    }
  }

  override fun close() {
    released.countDown()
    server.stop(0)
    pool.shutdownNow()
  }
}

/** One field of a protobuf message as it is on the wire. */
private class ProtoField(val number: Int, val value: Long, val bytes: ByteArray?)

/**
 * The part of the OTLP trace messages that tests look at, read from the protobuf wire format
 * (opentelemetry-proto `trace.proto`, `common.proto`, `resource.proto`). Field numbers are those of
 * the published protocol.
 */
internal object ProtoReader {
  fun spansOf(body: ByteArray): List<ReceivedSpan> =
      fields(body).filter { it.number == 1 }.flatMap { resourceSpans(it.bytes!!) }

  /** The names of the metrics of an `ExportMetricsServiceRequest` (`metrics.proto`). */
  fun metricNamesOf(body: ByteArray): List<String> =
      fields(body)
          .filter { it.number == 1 }
          .flatMap { resource -> fields(resource.bytes!!).filter { it.number == 2 } }
          .flatMap { scope -> fields(scope.bytes!!).filter { it.number == 2 } }
          .mapNotNull { metric -> fields(metric.bytes!!).firstOrNull { it.number == 1 } }
          .map { it.bytes!!.decodeToString() }

  private fun resourceSpans(message: ByteArray): List<ReceivedSpan> {
    val parts = fields(message)
    val service =
        parts
            .filter { it.number == 1 }
            .flatMap { fields(it.bytes!!) }
            .filter { it.number == 1 }
            .map { keyValue(it.bytes!!) }
            .firstOrNull { it.first == "service.name" }
            ?.second as? String
    return parts
        .filter { it.number == 2 }
        .flatMap { scope -> fields(scope.bytes!!).filter { it.number == 2 } }
        .map { span(it.bytes!!, service) }
  }

  private fun span(message: ByteArray, service: String?): ReceivedSpan {
    val parts = fields(message)

    fun bytesOf(n: Int) = parts.firstOrNull { it.number == n }?.bytes ?: ByteArray(0)

    fun valueOf(n: Int) = parts.firstOrNull { it.number == n }?.value ?: 0L

    val status = fields(bytesOf(15))
    return ReceivedSpan(
        traceId = hex(bytesOf(1)),
        spanId = hex(bytesOf(2)),
        parentSpanId = hex(bytesOf(4)),
        name = bytesOf(5).decodeToString(),
        startNanos = valueOf(7),
        endNanos = valueOf(8),
        attributes =
            parts
                .filter { it.number == 9 }
                .map { keyValue(it.bytes!!) }
                .associate { it.first to it.second },
        statusCode = status.firstOrNull { it.number == 3 }?.value?.toInt() ?: 0,
        statusMessage = status.firstOrNull { it.number == 2 }?.bytes?.decodeToString().orEmpty(),
        serviceName = service,
    )
  }

  private fun keyValue(message: ByteArray): Pair<String, Any?> {
    val parts = fields(message)
    val key = parts.first { it.number == 1 }.bytes!!.decodeToString()
    return key to parts.firstOrNull { it.number == 2 }?.let { anyValue(it.bytes!!) }
  }

  private fun anyValue(message: ByteArray): Any? {
    val part = fields(message).firstOrNull() ?: return null
    return when (part.number) {
      1 -> part.bytes!!.decodeToString()
      2 -> part.value != 0L
      3 -> part.value
      4 -> java.lang.Double.longBitsToDouble(part.value)
      5 -> fields(part.bytes!!).filter { it.number == 1 }.map { anyValue(it.bytes!!) }
      else -> null
    }
  }

  private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

  private fun fields(message: ByteArray): List<ProtoField> {
    val result = mutableListOf<ProtoField>()
    var position = 0

    fun varint(): Long {
      var shift = 0
      var value = 0L
      while (true) {
        val b = message[position++].toInt()
        value = value or ((b and 0x7f).toLong() shl shift)
        if (b and 0x80 == 0) return value
        shift += 7
      }
    }

    fun fixed(width: Int): Long {
      var value = 0L
      for (i in 0 until width) {
        value = value or ((message[position + i].toLong() and 0xff) shl (8 * i))
      }
      position += width
      return value
    }

    while (position < message.size) {
      val tag = varint().toInt()
      val number = tag ushr 3
      when (val wire = tag and 7) {
        0 -> result += ProtoField(number, varint(), null)
        1 -> result += ProtoField(number, fixed(8), null)
        2 -> {
          val length = varint().toInt()
          result += ProtoField(number, 0, message.copyOfRange(position, position + length))
          position += length
        }
        5 -> result += ProtoField(number, fixed(4), null)
        else -> error("unsupported wire type $wire")
      }
    }
    return result
  }
}
