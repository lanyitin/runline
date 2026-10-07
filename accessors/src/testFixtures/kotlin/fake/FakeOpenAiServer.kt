package dev.lawlan.runline.accessors.fake

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A request as the Fake read it off the socket. Header names are lower case. */
class FakeRequest(
    val method: String,
    /** The request target as sent: path and query. */
    val target: String,
    val headers: Map<String, List<String>>,
    val body: String,
) {
  val path: String = target.substringBefore('?')

  val query: Map<String, String> =
      target
          .substringAfter('?', "")
          .split('&')
          .filter { it.isNotEmpty() }
          .associate {
            URLDecoder.decode(it.substringBefore('='), StandardCharsets.UTF_8) to
                URLDecoder.decode(it.substringAfter('=', ""), StandardCharsets.UTF_8)
          }

  fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()
}

/**
 * A small OpenAI compatible HTTP server for tests: it really listens on a port and speaks HTTP/1.1
 * (one request per connection), so a client talks to it exactly as it would to lemonade or any
 * other service. By default it answers like such a service does (chat completions, completions,
 * embeddings, models, and the error shape of OpenAI); a test makes it do something else by setting
 * [script] (slow, stuck, redirecting, echoing what it got) or [responseDelayMillis]. It reports
 * what it saw: the requests, how many were being served at once (and at most), and how many clients
 * left before they were answered. The contract it keeps is in [OpenAiServerContract].
 */
class FakeOpenAiServer(
    /** When set, a request without `Authorization: Bearer <key>` is answered 401. */
    val requiredKey: String? = null,
    /** The root path of the API, as in `http://host/v1`. */
    val rootPath: String = "/v1",
) : AutoCloseable {
  private val socket = ServerSocket(0, 128, InetAddress.getByName("127.0.0.1"))
  val port: Int = socket.localPort

  /** The address of the API, with its root path: what a resource's base address is. */
  val baseUrl: String = "http://127.0.0.1:$port$rootPath"

  /** The address without the root path. */
  val origin: String = "http://127.0.0.1:$port"

  /** How long the default routes keep a client waiting before they answer. */
  @Volatile var responseDelayMillis: Long = 0

  /** How long the default streaming route waits before each chunk. */
  @Volatile var chunkDelayMillis: Long = 0

  /**
   * Answers a request itself; returns false to leave it to the default routes. Runs on the
   * connection's own thread, so it may take as long as it likes (and notice the client leaving).
   */
  @Volatile var script: ((FakeRequest, FakeResponse) -> Boolean)? = null

  private val seen = CopyOnWriteArrayList<FakeRequest>()
  private val current = AtomicInteger()
  private val peak = AtomicInteger()
  private val gone = AtomicInteger()
  private val closed = AtomicBoolean()

  /** Every request that was read completely, in the order it was read. */
  val requests: List<FakeRequest>
    get() = seen.toList()

  /** Requests being served now: read completely and not yet answered. */
  val inFlight: Int
    get() = current.get()

  /** The most requests ever being served at once since the start or the last [resetPeak]. */
  val peakInFlight: Int
    get() = peak.get()

  /** Clients that went away before they were answered. */
  val clientsGone: Int
    get() = gone.get()

  fun resetPeak() = peak.set(current.get())

  private val acceptor =
      Thread.ofPlatform().name("fake-openai-$port").daemon(true).start {
        while (!closed.get()) {
          val client =
              try {
                socket.accept()
              } catch (e: IOException) {
                break
              }
          Thread.ofPlatform().name("fake-openai-$port-conn").daemon(true).start { serve(client) }
        }
      }

  private fun serve(client: Socket) {
    client.use {
      val response = FakeResponse(client, this)
      try {
        val request = read(client.getInputStream()) ?: return
        seen += request
        response.started()
        try {
          val handled = script?.invoke(request, response) ?: false
          if (!handled && !response.done) route(request, response)
        } catch (e: ClientGone) {
          // The client left; what is left to do is to count it, which happened where it was seen.
        }
      } catch (e: IOException) {
        // The connection broke while it was read or written.
      } finally {
        response.finishedByHost()
      }
    }
  }

  private fun read(input: InputStream): FakeRequest? {
    val buffered = BufferedInputStream(input)
    val requestLine = line(buffered) ?: return null
    if (requestLine.isEmpty()) return null
    val parts = requestLine.split(' ')
    val headers = LinkedHashMap<String, MutableList<String>>()
    while (true) {
      val header = line(buffered) ?: return null
      if (header.isEmpty()) break
      val name = header.substringBefore(':').trim().lowercase()
      headers.getOrPut(name) { mutableListOf() } += header.substringAfter(':').trim()
    }
    val body =
        if (headers["transfer-encoding"]?.any { it.equals("chunked", true) } == true) {
          chunked(buffered)
        } else {
          buffered.readNBytes(headers["content-length"]?.firstOrNull()?.toInt() ?: 0)
        }
    return FakeRequest(parts[0], parts[1], headers, body.toString(StandardCharsets.UTF_8))
  }

  private fun line(input: InputStream): String? {
    val out = ByteArrayOutputStream()
    while (true) {
      val b = input.read()
      if (b < 0) return if (out.size() == 0) null else out.toString(StandardCharsets.ISO_8859_1)
      if (b == '\n'.code) return out.toString(StandardCharsets.ISO_8859_1).trimEnd('\r')
      out.write(b)
    }
  }

  private fun chunked(input: InputStream): ByteArray {
    val out = ByteArrayOutputStream()
    while (true) {
      val size = (line(input) ?: return out.toByteArray()).substringBefore(';').trim().toInt(16)
      if (size == 0) {
        line(input)
        return out.toByteArray()
      }
      out.write(input.readNBytes(size))
      line(input)
    }
  }

  // What a real service answers; the contract tests say how closely.

  private fun route(request: FakeRequest, response: FakeResponse) {
    if (requiredKey != null && request.header("authorization") != "Bearer $requiredKey") {
      return response.json(
          401,
          error("Incorrect API key provided.", "invalid_request_error", "invalid_api_key"),
          mapOf("WWW-Authenticate" to "Bearer realm=\"fake\""),
      )
    }
    if (!request.path.startsWith("$rootPath/")) {
      return response.json(
          404,
          error("Unknown path ${request.path}", "invalid_request_error", "not_found"),
      )
    }
    if (responseDelayMillis > 0) response.pause(responseDelayMillis)
    val sub = request.path.removePrefix(rootPath)
    val body: JsonObject? =
        if (request.method == "POST") {
          try {
            Json.parseToJsonElement(request.body) as? JsonObject
          } catch (e: SerializationException) {
            null
          }
              ?: return response.json(
                  400,
                  error("The body is not a JSON object.", "invalid_request_error", "invalid_json"),
              )
        } else null
    val model = body?.get("model")?.jsonPrimitive?.content ?: "fake-model"
    when {
      request.method == "POST" && sub == "/chat/completions" && body?.isStreamed() == true ->
          streamChat(model, body, response)
      request.method == "POST" && sub == "/chat/completions" ->
          response.json(
              200,
              """{"id":"chatcmpl-fake","object":"chat.completion","created":1,"model":"$model","choices":[{"index":0,"message":{"role":"assistant","content":"Hello from the fake"},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":5,"total_tokens":8}}""",
          )
      request.method == "POST" && sub == "/completions" ->
          response.json(
              200,
              """{"id":"cmpl-fake","object":"text_completion","created":1,"model":"$model","choices":[{"index":0,"text":" upon a time","finish_reason":"length"}],"usage":{"prompt_tokens":1,"completion_tokens":3,"total_tokens":4}}""",
          )
      request.method == "POST" && sub == "/embeddings" ->
          response.json(
              200,
              """{"object":"list","data":[{"object":"embedding","index":0,"embedding":[0.1,0.2,0.3]}],"model":"$model","usage":{"prompt_tokens":2,"total_tokens":2}}""",
          )
      request.method == "GET" && sub == "/models" ->
          response.json(
              200,
              """{"object":"list","data":[${MODELS.joinToString(",") { modelObject(it) }}]}""",
          )
      request.method == "GET" && sub.startsWith("/models/") -> {
        val id = sub.removePrefix("/models/")
        if (id in MODELS) response.json(200, modelObject(id))
        else
            response.json(
                404,
                error("The model '$id' does not exist", "invalid_request_error", "model_not_found"),
            )
      }
      else ->
          response.json(
              404,
              error("Unknown path ${request.path}", "invalid_request_error", "not_found"),
          )
    }
  }

  private fun JsonObject.isStreamed() = this["stream"]?.jsonPrimitive?.content == "true"

  /**
   * What a service answers to a streamed chat completion: a chunked `text/event-stream` of
   * `chat.completion.chunk` events (one per word, [chunkDelayMillis] apart), a last chunk with the
   * usage when the request asked for it, and `[DONE]`.
   */
  private fun streamChat(model: String, body: JsonObject, response: FakeResponse) {
    response.beginChunked(200, mapOf("Content-Type" to "text/event-stream"))
    fun chunk(delta: String, finish: String? = null) =
        """{"id":"chatcmpl-fake","object":"chat.completion.chunk","created":1,"model":"$model","choices":[{"index":0,"delta":$delta,"finish_reason":${finish?.let { "\"$it\"" } ?: "null"}}]}"""
    response.event(chunk("""{"role":"assistant","content":""}"""))
    for (word in STREAM_WORDS) {
      if (chunkDelayMillis > 0) response.pause(chunkDelayMillis)
      response.event(chunk("""{"content":"$word"}"""))
    }
    response.event(chunk("{}", "stop"))
    val usage = body["stream_options"] as? JsonObject
    if (usage?.get("include_usage")?.jsonPrimitive?.content == "true") {
      response.event(
          """{"id":"chatcmpl-fake","object":"chat.completion.chunk","created":1,"model":"$model","choices":[],"usage":{"prompt_tokens":3,"completion_tokens":${STREAM_WORDS.size},"total_tokens":${3 + STREAM_WORDS.size}}}"""
      )
    }
    response.event("[DONE]")
    response.endChunked()
  }

  private fun modelObject(id: String) =
      """{"id":"$id","object":"model","created":1,"owned_by":"fake"}"""

  private fun error(message: String, type: String, code: String) =
      """{"error":{"message":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(message))},"type":"$type","param":null,"code":"$code"}}"""

  internal fun started() {
    val now = current.incrementAndGet()
    peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
  }

  internal fun ended() {
    current.decrementAndGet()
  }

  internal fun clientLeft() {
    gone.incrementAndGet()
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    socket.close()
    acceptor.join(2000)
  }

  private companion object {
    val MODELS = listOf("fake-model", "fake-embedding")
    val STREAM_WORDS = listOf("Hello", " from", " the", " fake")
  }
}

/** Thrown inside a script or a route when the client has left: nothing more can be answered. */
class ClientGone : RuntimeException("the client left", null, false, false)

/**
 * The answer to one request, as a script writes it. Whatever is written is a real HTTP/1.1 response
 * on the client's socket; the connection is closed when the exchange ends.
 */
class FakeResponse
internal constructor(private val client: Socket, private val server: FakeOpenAiServer) {
  private val counted = AtomicBoolean()
  private val finished = AtomicBoolean()
  private var departed = false
  private val out = client.getOutputStream()

  /** Whether the response is complete or was broken off. */
  val done: Boolean
    get() = finished.get()

  internal fun started() {
    counted.set(true)
    server.started()
  }

  /** The request stops counting as being served; the first call does it, the others do nothing. */
  private fun uncount() {
    if (counted.compareAndSet(true, false)) server.ended()
  }

  internal fun finishedByHost() {
    uncount()
    finished.set(true)
  }

  /**
   * Waits up to [millis] and says whether the client is still there; a client that is not is
   * counted once as gone. Use [ClientGone] to stop at once instead: [pause] throws it.
   */
  fun pause(millis: Long): Boolean {
    val deadline = System.nanoTime() + millis * 1_000_000
    client.soTimeout = 20
    while (true) {
      val left = (deadline - System.nanoTime()) / 1_000_000
      if (left <= 0) return true
      if (!isHere(minOf(left, 20))) {
        noteLeft()
        throw ClientGone()
      }
    }
  }

  /** Waits for as long as the client stays; returns when it leaves (and counts it). */
  fun hang() {
    client.soTimeout = 20
    while (isHere(20)) {
      // wait
    }
    noteLeft()
    throw ClientGone()
  }

  /** Whether the client is still connected, looking for at most [millis]. */
  private fun isHere(millis: Long): Boolean {
    client.soTimeout = maxOf(millis, 1).toInt()
    return try {
      client.getInputStream().read() >= 0
    } catch (e: SocketTimeoutException) {
      true
    } catch (e: IOException) {
      false
    }
  }

  private fun noteLeft() {
    if (!departed) {
      departed = true
      server.clientLeft()
      uncount()
      finished.set(true)
    }
  }

  /** A complete JSON answer. */
  fun json(status: Int, body: String, headers: Map<String, String> = emptyMap()) =
      complete(status, body.encodeToByteArray(), headers + ("Content-Type" to "application/json"))

  /** A complete answer with any content type. */
  fun complete(status: Int, body: ByteArray, headers: Map<String, String> = emptyMap()) {
    begin(status, headers, body.size.toLong())
    uncount() // the request no longer counts when the last bytes go out, not after them
    write(body)
    finish()
  }

  /** A redirect to [location]. */
  fun redirect(status: Int, location: String) =
      complete(status, ByteArray(0), mapOf("Location" to location))

  /** Starts an answer; [contentLength] is how many bytes will follow (null: until the close). */
  fun begin(status: Int, headers: Map<String, String> = emptyMap(), contentLength: Long? = null) {
    val head = StringBuilder("HTTP/1.1 $status ${reason(status)}\r\n")
    for ((name, value) in headers) head.append(name).append(": ").append(value).append("\r\n")
    if (contentLength != null) head.append("Content-Length: ").append(contentLength).append("\r\n")
    head.append("Connection: close\r\n\r\n")
    send(head.toString().toByteArray(StandardCharsets.ISO_8859_1))
  }

  /** Starts an answer in chunked transfer encoding, as a streaming service does. */
  fun beginChunked(status: Int, headers: Map<String, String> = emptyMap()) {
    val head = StringBuilder("HTTP/1.1 $status ${reason(status)}\r\n")
    for ((name, value) in headers) head.append(name).append(": ").append(value).append("\r\n")
    head.append("Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n")
    send(head.toString().toByteArray(StandardCharsets.ISO_8859_1))
  }

  /** One chunk of a chunked answer; the client can read it as soon as this returns. */
  fun chunk(bytes: ByteArray) {
    send(
        Integer.toHexString(bytes.size).toByteArray(StandardCharsets.ISO_8859_1) +
            CRLF +
            bytes +
            CRLF
    )
  }

  /** One server-sent event with [data], as one chunk. */
  fun event(data: String) = chunk("data: $data\n\n".encodeToByteArray())

  /** The last chunk of a chunked answer: the answer is complete. */
  fun endChunked() {
    uncount()
    send("0\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
    finish()
  }

  fun write(bytes: ByteArray) = send(bytes)

  fun write(text: String) = send(text.encodeToByteArray())

  /** The answer is complete. */
  fun finish() {
    uncount()
    try {
      out.flush()
    } catch (e: IOException) {
      // nothing left to say
    }
    finished.set(true)
  }

  /** Breaks the connection off at once, as a service that died would. */
  fun abort() {
    uncount()
    finished.set(true)
    try {
      client.setSoLinger(true, 0)
      client.close()
    } catch (e: IOException) {
      // already closed
    }
  }

  private companion object {
    val CRLF = "\r\n".toByteArray(StandardCharsets.ISO_8859_1)
  }

  private fun send(bytes: ByteArray) {
    try {
      out.write(bytes)
      out.flush()
    } catch (e: IOException) {
      noteLeft()
      throw ClientGone()
    }
  }

  private fun reason(status: Int) =
      when (status) {
        200 -> "OK"
        301 -> "Moved Permanently"
        302 -> "Found"
        307 -> "Temporary Redirect"
        308 -> "Permanent Redirect"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        else -> "Status"
      }
}
