package dev.lawlan.runline.accessors.openai

import dev.lawlan.runline.accessors.ResourceBinding
import dev.lawlan.runline.accessors.ResourceOperationFailure
import dev.lawlan.runline.accessors.tls.ResourceTls
import dev.lawlan.runline.accessors.tls.TlsContext
import dev.lawlan.runline.accessors.tls.TlsFailure
import dev.lawlan.runline.core.ResourceFailure
import dev.lawlan.runline.core.ResourceTypes
import dev.lawlan.runline.runner.SharedThreads
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** What the resource sends the service as its key. */
sealed interface OpenAiCredential {
  /** The service needs no key. */
  data object None : OpenAiCredential

  /** The key; it is never shown, and never part of a message. */
  class Key(internal val value: String) : OpenAiCredential {
    override fun toString() = "Key(***)"
  }

  /** The resource names a key that the keystore cannot give: nothing can be sent. */
  data object Unavailable : OpenAiCredential
}

/**
 * The requests of one run to the service behind one `openai-compatible` resource (ADR-019): it is
 * bound to the resource's settings and key as they were when the run got the resource, and to
 * nothing else. A call becomes a request of the catalog entry's method and path to the resource's
 * address, with the key and the administrator's headers and nothing of the pipeline's; the answer
 * comes back whole, without credentials in its headers, or as a category of failure.
 */
class OpenAiBinding(
    private val resource: String,
    private val settings: OpenAiSettings,
    private val credential: OpenAiCredential = OpenAiCredential.None,
    private val observer: OpenAiObserver = OpenAiObserver.NONE,
    /** The certificates of an `https` service; null: the JVM's default trust and no client one. */
    private val tls: ResourceTls? = null,
) : ResourceBinding {
  override val type: String = ResourceTypes.OPENAI_COMPATIBLE

  /**
   * The TLS of an `https` service, the binding's own (WI-52): only the resource's trusted
   * certificates when it names any (otherwise the JVM's default trust), its client certificate, and
   * the host always checked. Nothing of it is shared with another binding or the JVM's defaults.
   */
  private val tlsContext: TlsContext? =
      if (settings.baseUrl.scheme == "https") (tls ?: ResourceTls(emptyList(), null)).newContext()
      else null

  /** The category of a failure of TLS among the causes of [failure], when it is one. */
  internal fun tlsFailureOf(failure: Throwable?): TlsFailure? = failure?.let {
    tlsContext?.classify(it)
  }

  /** One client per connect limit, which a client cannot vary by request; usually just one. */
  private val clients = ConcurrentHashMap<Long, HttpClient>()

  private fun clientFor(connectMillis: Long): HttpClient =
      clients.computeIfAbsent(connectMillis) {
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofMillis(connectMillis))
            .apply { tlsContext?.let { sslContext(it.sslContext) } }
            .build()
      }

  /** How many requests this run has in flight, at most; the capacity limits the runs. */
  private val quota = RequestQuota(settings.requestsPerRun)

  /** The calls that have a request in flight, which an abort cuts. */
  private val live: MutableSet<Call> = ConcurrentHashMap.newKeySet()

  @Volatile private var aborted = false

  override fun execute(operation: String, arguments: Map<String, Any?>): Any? =
      when (operation) {
        OPERATION -> call(arguments, streaming = false)
        DOWNLOAD -> call(arguments, streaming = false, binary = true)
        STREAM_OPEN -> call(arguments, streaming = true, binary = arguments["binary"] == true)
        STREAM_NEXT -> streamOf(arguments).next()
        STREAM_CLOSE -> streamOf(arguments).end(null).let { null }
        else -> error("unknown operation $operation")
      }

  /** The streams opened and not yet ended, by the number a pipeline pulls them with. */
  private val streams = ConcurrentHashMap<Long, OpenStream>()
  private val streamIds = AtomicLong()

  private fun streamOf(arguments: Map<String, Any?>): OpenStream =
      streams[arguments["stream"] as? Long]
          ?: throw ResourceOperationFailure(ResourceFailure.INVALID_ARGUMENT)

  /**
   * A stream a pipeline pulls: its events, as they come. It is the call that opened it, which goes
   * on holding its share of requests until the stream ends.
   */
  private inner class OpenStream(
      private val call: Call,
      private val report: Report,
      private val endpoint: String,
      private val events: ServerSentEvents?,
      /** For a stream of bytes: the wire to read them from. */
      private val wire: Wire?,
  ) {
    /** Set once, when the stream is over, with why: what every later pull comes to. */
    private val over = AtomicReference<Ending>()

    fun next(): Any? {
      over.get()?.let { ending ->
        ending.failure?.let { throw ResourceOperationFailure(it) }
        return null
      }
      val data =
          try {
            stopped(call)?.let { throw it }
            val waitStart = System.nanoTime()
            (if (wire != null) chunk(wire) else events!!.next()).also {
              val waited = millisSince(waitStart)
              report.maxChunkGapMillis = maxOf(report.maxChunkGapMillis ?: 0, waited)
            }
          } catch (e: ResourceOperationFailure) {
            end(e.failure)
            throw e
          }
      if (data == null || data == "[DONE]") {
        end(null)
        return null
      }
      if (data is ByteArray) return data
      data as String
      if (data.contains("\"usage\"")) usageOf(data)?.let { report.usage = it }
      // A service that echoes the key does not give it to the pipeline through a stream either.
      val key = (credential as? OpenAiCredential.Key)?.value
      return if (key != null) data.replace(key, "***") else data
    }

    /**
     * The next bytes of the answer, as many as are there (some, at most a chunk); null at its end.
     */
    private fun chunk(wire: Wire): ByteArray? {
      while (true) {
        val buffer = ByteArray(BUFFER)
        val count = wire.read(buffer)
        if (count < 0) {
          val rest = held
          held = ByteArray(0)
          return if (rest.isEmpty()) null else rest
        }
        if (scan == null) return buffer.copyOf(count)
        if (scan.finds(buffer, count)) throw echoed()
        // The last bytes that could begin a key wait for the next piece before they are given out.
        val data = held + buffer.copyOf(count)
        val keep = minOf(scan.holdBack, data.size)
        held = data.copyOfRange(data.size - keep, data.size)
        val out = data.copyOfRange(0, data.size - keep)
        if (out.isNotEmpty()) return out
      }
    }

    private val scan = scanForKey()
    private var held = ByteArray(0)

    /** The stream is over, whichever way: the connection goes, the share is given back. */
    fun end(reason: ResourceFailure?) {
      if (!over.compareAndSet(null, Ending(reason))) return
      runCatching { call.stream?.close() }
      call.release()
      if (reason == null) {
        report.firstByteAt?.let { report.generationMillis = millisSince(it) }
      }
      report.finish(endpoint, reason, null)
    }
  }

  private fun call(
      arguments: Map<String, Any?>,
      streaming: Boolean,
      binary: Boolean = false,
  ): Any? {
    val endpoint =
        (arguments["endpoint"] as? String)?.let { OpenAiEndpoints.find(it)?.id } ?: "unknown"
    val report = Report()
    var result: Throwable? = null
    try {
      return run(arguments, endpoint, report, streaming, binary)
    } catch (e: Throwable) {
      result = e
      throw e
    } finally {
      val failure =
          when (result) {
            null -> null
            is ResourceOperationFailure -> result.failure
            else -> ResourceFailure.FAILED
          }
      if (!report.handedOver) {
        report.tlsFailure = tlsFailureOf(result)
        report.finish(endpoint, failure, (result as? ResourceOperationFailure)?.status)
      }
    }
  }

  /** What an observer is told about one call, gathered as it goes. */
  private inner class Report {
    var sent = false
    var quotaWaitMillis = 0L
    var acquiredAt = 0L
    var firstByteMillis: Long? = null
    var generationMillis: Long? = null
    var usage: OpenAiTokenUsage? = null
    var status: Int? = null
    var tlsFailure: TlsFailure? = null

    /** For a stream: when its first byte came, and the longest wait for an event so far. */
    @Volatile var firstByteAt: Long? = null
    @Volatile var maxChunkGapMillis: Long? = null

    /** A stream took the call over, and tells the observer when it ends. */
    @Volatile var handedOver = false

    /** What the observer gave for a stream when it began. */
    @Volatile var streamHandle: Any? = null
    private val finished = AtomicBoolean()

    fun finish(endpoint: String, failure: ResourceFailure?, failedStatus: Int?) {
      if (!finished.compareAndSet(false, true)) return
      val outcome =
          OpenAiOutcome(
              failure,
              failedStatus ?: status,
              sent,
              quotaWaitMillis,
              if (sent) millisSince(acquiredAt) else 0,
              firstByteMillis,
              generationMillis,
              usage,
              maxChunkGapMillis,
              tlsFailure,
          )
      runCatching { observer.finished(resource, endpoint, outcome) }
      if (handedOver)
          runCatching { observer.streamFinished(streamHandle, resource, endpoint, outcome) }
    }
  }

  /** Sends a plan that did not come from a pipeline; used by [OpenAiProbe]. */
  internal fun send(plan: OpenAiRequestPlan): Any? {
    val report = Report()
    var failure: ResourceFailure? = null
    try {
      return run(plan, plan.endpoint.id, report)
    } catch (e: ResourceOperationFailure) {
      failure = e.failure
      report.tlsFailure = tlsFailureOf(e)
      throw e
    } finally {
      report.finish(plan.endpoint.id, failure, null)
    }
  }

  private fun run(
      arguments: Map<String, Any?>,
      endpoint: String,
      report: Report,
      streaming: Boolean,
      binary: Boolean,
  ): Any? {
    if (aborted) throw ResourceOperationFailure(ResourceFailure.CANCELLED)
    return run(OpenAiRequestPlan.of(settings, arguments, streaming, binary), endpoint, report)
  }

  private fun run(plan: OpenAiRequestPlan, endpoint: String, report: Report): Any? {
    if (aborted) throw ResourceOperationFailure(ResourceFailure.CANCELLED)
    if (credential is OpenAiCredential.Unavailable) {
      throw ResourceOperationFailure(ResourceFailure.SECRET_UNAVAILABLE)
    }
    // A place the answer may not be written to is found out before the service is asked.
    plan.output?.let { it.directory.checkTarget(it.relative) }
    val waitStart = System.nanoTime()
    val got =
        try {
          quota.acquire(plan.limits.quotaWaitMillis)
        } catch (e: InterruptedException) {
          Thread.currentThread().interrupt()
          throw ResourceOperationFailure(ResourceFailure.CANCELLED, e)
        } catch (e: QuotaAborted) {
          throw ResourceOperationFailure(ResourceFailure.CANCELLED, e)
        }
    report.quotaWaitMillis = millisSince(waitStart)
    report.acquiredAt = System.nanoTime()
    if (!got) throw ResourceOperationFailure(ResourceFailure.QUOTA_WAIT_TIMEOUT)
    val call = Call(plan.limits)
    live += call
    try {
      if (aborted) throw ResourceOperationFailure(ResourceFailure.CANCELLED)
      report.sent = true
      runCatching { observer.started(resource, endpoint) }
      if (plan.streaming)
          report.streamHandle =
              runCatching { observer.streamStarted(resource, endpoint) }.getOrNull()
      return exchange(call, plan, report)
    } finally {
      if (!call.keptOpen) call.release()
    }
  }

  override fun abort() {
    aborted = true
    quota.abort()
    live.forEach { it.stop(ResourceFailure.CANCELLED) }
    streams.values.forEach { it.end(ResourceFailure.CANCELLED) }
  }

  override fun close() {
    clients.values.forEach { it.shutdownNow() }
    clients.clear()
  }

  /** One call in progress: what can stop it from outside, and why it was stopped. */
  private inner class Call(val limits: OpenAiLimits) {
    @Volatile var stoppedFor: ResourceFailure? = null
    @Volatile var future: CompletableFuture<*>? = null
    @Volatile var stream: InputStream? = null
    @Volatile var total: ScheduledFuture<*>? = null

    /** What the call has open besides the connection: the files an upload reads from. */
    private val holdings: MutableList<Closeable> = CopyOnWriteArrayList()

    fun holding(closeable: Closeable) {
      holdings += closeable
    }

    /** The stream this call has become, which is over as soon as the call is stopped. */
    @Volatile var owner: OpenStream? = null

    /** A stream took the call over: it ends the call, not the exchange that opened it. */
    @Volatile var keptOpen = false
    private val released = AtomicBoolean()

    /** The call is over: its timer stops, it can no longer be cut, and its share is free again. */
    fun release() {
      if (!released.compareAndSet(false, true)) return
      total?.cancel(false)
      holdings.forEach { runCatching { it.close() } }
      // Whatever ended the call, the connection goes with it.
      runCatching { stream?.close() }
      live -= this
      quota.release()
    }

    /** Stops the call for [reason]; the first reason stays. */
    fun stop(reason: ResourceFailure) {
      if (stoppedFor == null) stoppedFor = reason
      future?.cancel(true)
      runCatching { stream?.close() }
      owner?.end(reason)
    }
  }

  private fun exchange(call: Call, plan: OpenAiRequestPlan, report: Report): Any? {
    call.total =
        call.limits.totalMillis?.let {
          TIMERS.schedule({ call.stop(ResourceFailure.TOTAL_TIMEOUT) }, it, TimeUnit.MILLISECONDS)
        }
    return send(call, plan, report)
  }

  private fun send(call: Call, plan: OpenAiRequestPlan, report: Report): Any? {
    val sentAt = System.nanoTime()
    var uri = plan.uri
    var method = plan.method
    var body = plan.body
    var upload = plan.upload
    var hops = 0
    while (true) {
      val client = clientFor(call.limits.connectMillis)
      val content =
          upload
              ?.open(plan.sizes.request)
              ?.also {
                call.holding(it)
                // The idle limit also covers the sending: a form that stops going out is idle.
              }
              ?.let { opened ->
                Progress(call, opened.stream).also { call.holding(it) }.let(opened::reading)
              }
      val response =
          awaitHeaders(
              call,
              client,
              request(uri, method, body, content, call.limits, plan.streaming, plan.binary),
          )
      val status = response.statusCode()
      call.stream = response.body()
      report.status = status
      report.firstByteMillis = millisSince(sentAt)
      if (status in REDIRECTS) {
        response.body().close()
        val next = redirectTarget(uri, response) ?: throw blocked(status)
        if (++hops > MAX_REDIRECTS) throw blocked(status)
        if (status == 303 || (status != 307 && status != 308 && method == "POST")) {
          method = "GET"
          body = null
          upload = null
        }
        uri = next
        continue
      }
      if (status !in 200..299) {
        response.body().close()
        throw ResourceOperationFailure(categoryOf(status), null, status)
      }
      val declared = response.headers().firstValueAsLong("content-length")
      val limit = if (plan.output != null) plan.sizes.download else plan.sizes.response
      if (declared.isPresent && declared.asLong > limit) {
        response.body().close()
        throw ResourceOperationFailure(ResourceFailure.RESPONSE_TOO_LARGE, null, status)
      }
      if (plan.streaming)
          return startStream(
              call,
              response,
              status,
              report,
              plan.endpoint.id,
              sentAt,
              plan.sizes.response,
              plan.binary,
          )
      val readStart = System.nanoTime()
      if (plan.output != null) {
        val written = writeFile(call, response.body(), status, plan.output, limit)
        report.generationMillis = millisSince(readStart)
        return mapOf(
            "status" to status,
            "headers" to answerHeaders(response.headers().map()),
            "path" to plan.output.directory.relativeName(plan.output.relative),
            "size" to written,
        )
      }
      if (plan.binary) {
        val bytes = readBytes(call, response.body(), status, limit)
        report.generationMillis = millisSince(readStart)
        return mapOf(
            "status" to status,
            "headers" to answerHeaders(response.headers().map()),
            "bytes" to bytes,
        )
      }
      val text = readBody(call, response.body(), status, limit)
      report.generationMillis = millisSince(readStart)
      report.usage = usageOf(text)
      return mapOf(
          "status" to status,
          "headers" to answerHeaders(response.headers().map()),
          "body" to text,
      )
    }
  }

  /**
   * The bytes of a form as the client takes them: the idle limit runs from each taking to the next,
   * so an upload that the service stops reading, or a client that stops sending, ends as idle. With
   * the send buffer bounded ([UploadSendBuffer]), a taking follows the service's reading closely.
   */
  private inner class Progress(private val call: Call, private val inner: InputStream) :
      InputStream(), Closeable {
    @Volatile private var timer: ScheduledFuture<*>? = null

    private fun arm() {
      timer?.cancel(false)
      timer =
          TIMERS.schedule(
              { call.stop(ResourceFailure.IDLE_TIMEOUT) },
              call.limits.idleMillis,
              TimeUnit.MILLISECONDS,
          )
    }

    private fun disarm() {
      timer?.cancel(false)
      timer = null
    }

    override fun read(): Int {
      arm()
      return inner.read().also { if (it < 0) disarm() }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
      arm()
      return inner.read(buffer, offset, length).also { if (it < 0) disarm() }
    }

    override fun close() {
      disarm()
      inner.close()
    }
  }

  /** The bytes of a stream as they come, under the limits on time and open to being cut. */
  private inner class Wire(
      private val call: Call,
      private val input: InputStream,
      private val sentAt: Long,
      private val report: Report,
  ) {
    private var begun = false

    fun read(buffer: ByteArray): Int {
      // Until the first byte of the answer it is the first byte limit that counts, from the
      // request;
      // after it, the gap between two reads.
      val timer =
          TIMERS.schedule(
              {
                call.stop(
                    if (begun) ResourceFailure.IDLE_TIMEOUT else ResourceFailure.FIRST_BYTE_TIMEOUT
                )
              },
              if (begun) call.limits.idleMillis
              else maxOf(call.limits.firstByteMillis - millisSince(sentAt), 0),
              TimeUnit.MILLISECONDS,
          )
      val count =
          try {
            input.read(buffer)
          } catch (e: IOException) {
            throw stopped(call)
                ?: if (Thread.currentThread().isInterrupted || e.cause is InterruptedException) {
                  interrupted(call, e)
                } else {
                  classifyIo(e)
                }
          } finally {
            timer.cancel(false)
          }
      stopped(call)?.let { throw it }
      if (count > 0 && !begun) {
        begun = true
        report.firstByteMillis = millisSince(sentAt)
        report.firstByteAt = System.nanoTime()
      }
      return count
    }
  }

  private fun startStream(
      call: Call,
      response: HttpResponse<InputStream>,
      status: Int,
      report: Report,
      endpoint: String,
      sentAt: Long,
      maxEventBytes: Long,
      binary: Boolean,
  ): Map<String, Any?> {
    val wire = Wire(call, response.body(), sentAt, report)
    val id = streamIds.incrementAndGet()
    val events = if (binary) null else ServerSentEvents(maxEventBytes.toInt()) { wire.read(it) }
    val headers = answerHeaders(response.headers().map())
    val opened = OpenStream(call, report, endpoint, events, if (binary) wire else null)
    call.owner = opened
    streams[id] = opened
    call.keptOpen = true
    report.handedOver = true
    // An abort that came while the stream was being made did not find it: it is not given out.
    val stopped = if (aborted) ResourceFailure.CANCELLED else call.stoppedFor
    if (stopped != null) {
      opened.end(stopped)
      throw ResourceOperationFailure(stopped)
    }
    return mapOf("stream" to id, "status" to status, "headers" to headers)
  }

  private fun awaitHeaders(
      call: Call,
      client: HttpClient,
      request: HttpRequest,
  ): HttpResponse<InputStream> {
    val future = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
    call.future = future
    try {
      return future.get()
    } catch (e: InterruptedException) {
      throw interrupted(call, e)
    } catch (e: ExecutionException) {
      throw stopped(call) ?: classify(call, e.cause ?: e)
    } catch (e: CancellationException) {
      throw stopped(call) ?: ResourceOperationFailure(ResourceFailure.CANCELLED, e)
    }
  }

  /** The answer, read in pieces; the idle limit is on each wait for the next piece. */
  /** The answer, read in pieces and written into the file of a scope, which it replaces whole. */
  private fun writeFile(
      call: Call,
      stream: InputStream,
      status: Int,
      target: DownloadTarget,
      limit: Long,
  ): Long {
    val buffer = ByteArray(BUFFER)
    val scan = scanForKey()
    target.directory.openForWrite(target.relative, target.maxBytes).use { file ->
      while (true) {
        val idle =
            TIMERS.schedule(
                { call.stop(ResourceFailure.IDLE_TIMEOUT) },
                call.limits.idleMillis,
                TimeUnit.MILLISECONDS,
            )
        val read =
            try {
              stream.read(buffer)
            } catch (e: IOException) {
              throw stopped(call) ?: classify(call, e)
            } finally {
              idle.cancel(false)
            }
        stopped(call)?.let { throw it }
        if (read < 0) break
        if (file.size + read > limit) {
          runCatching { stream.close() }
          throw ResourceOperationFailure(ResourceFailure.RESPONSE_TOO_LARGE, null, status)
        }
        // Refused whole: the file being written is deleted with the exception, key and all.
        if (scan?.finds(buffer, read) == true) throw echoed()
        file.write(buffer, 0, read)
      }
      file.commit()
      return file.size
    }
  }

  private fun readBody(call: Call, stream: InputStream, status: Int, limit: Long): String =
      readBytes(call, stream, status, limit, binary = false).toString(StandardCharsets.UTF_8)

  private fun readBytes(
      call: Call,
      stream: InputStream,
      status: Int,
      limit: Long,
      binary: Boolean = true,
  ): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(BUFFER)
    // JSON text is the pipeline's to use, as in WI-46; bytes are checked for the key.
    val scan = if (binary) scanForKey() else null
    while (true) {
      val idle =
          TIMERS.schedule(
              { call.stop(ResourceFailure.IDLE_TIMEOUT) },
              call.limits.idleMillis,
              TimeUnit.MILLISECONDS,
          )
      val read =
          try {
            stream.read(buffer)
          } catch (e: IOException) {
            throw stopped(call) ?: classify(call, e)
          } finally {
            idle.cancel(false)
          }
      stopped(call)?.let { throw it }
      if (read < 0) break
      if (out.size() + read > limit) {
        runCatching { stream.close() }
        throw ResourceOperationFailure(ResourceFailure.RESPONSE_TOO_LARGE, null, status)
      }
      // A key that a service echoes into an answer does not reach the pipeline, whole or in part.
      if (scan?.finds(buffer, read) == true) throw echoed()
      out.write(buffer, 0, read)
    }
    return out.toByteArray()
  }

  /** The failure for a call that was stopped from outside, or null when it was not. */
  private fun stopped(call: Call): ResourceOperationFailure? =
      call.stoppedFor?.let { ResourceOperationFailure(it) }

  /** The thread was interrupted (the run was cancelled): the call stops and the thread stays so. */
  private fun interrupted(call: Call, cause: Throwable): ResourceOperationFailure {
    call.stop(ResourceFailure.CANCELLED)
    Thread.currentThread().interrupt()
    return ResourceOperationFailure(ResourceFailure.CANCELLED, cause)
  }

  /** What the client threw, as the category it comes to. */
  private fun classify(call: Call, cause: Throwable): ResourceOperationFailure =
      when {
        Thread.currentThread().isInterrupted || cause is InterruptedException ->
            interrupted(call, cause)
        else -> classifyIo(cause)
      }

  private fun classifyIo(cause: Throwable): ResourceOperationFailure =
      when (cause) {
        is HttpConnectTimeoutException ->
            ResourceOperationFailure(ResourceFailure.CONNECT_TIMEOUT, cause)
        is HttpTimeoutException ->
            ResourceOperationFailure(ResourceFailure.FIRST_BYTE_TIMEOUT, cause)
        is IOException -> ResourceOperationFailure(ResourceFailure.CONNECTION_FAILED, cause)
        else -> throw cause
      }

  private fun request(
      uri: URI,
      method: String,
      body: ByteArray?,
      upload: OpenedUpload?,
      limits: OpenAiLimits,
      streaming: Boolean,
      binary: Boolean,
  ): HttpRequest {
    val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(limits.firstByteMillis))
    builder.header(
        "Accept",
        when {
          binary -> "*/*"
          streaming -> "text/event-stream"
          else -> "application/json"
        },
    )
    if (body != null) builder.header("Content-Type", "application/json")
    if (upload != null) builder.header("Content-Type", upload.contentType)
    (credential as? OpenAiCredential.Key)?.let {
      builder.header("Authorization", "Bearer ${it.value}")
    }
    settings.organization?.let { builder.header("OpenAI-Organization", it) }
    settings.project?.let { builder.header("OpenAI-Project", it) }
    settings.headers.forEach { (name, value) -> builder.header(name, value) }
    builder.method(
        method,
        when {
          upload != null ->
              HttpRequest.BodyPublishers.fromPublisher(
                  HttpRequest.BodyPublishers.ofInputStream { upload.stream },
                  upload.length,
              )
          body != null -> HttpRequest.BodyPublishers.ofByteArray(body)
          else -> HttpRequest.BodyPublishers.noBody()
        },
    )
    return builder.build()
  }

  /**
   * Where a redirect leads, if that is inside the base address: the same scheme, host and port, and
   * a path at or below the base path. Anything else, or no place at all, is null.
   */
  private fun redirectTarget(from: URI, response: HttpResponse<*>): URI? {
    val location = response.headers().firstValue("location").orElse(null) ?: return null
    val target =
        try {
          from.resolve(location).normalize()
        } catch (e: IllegalArgumentException) {
          return null
        }
    val base = settings.baseUrl
    if (!target.scheme.equals(base.scheme, ignoreCase = true)) return null
    if (!target.host.equals(base.host, ignoreCase = true)) return null
    if (portOf(target) != portOf(base)) return null
    if (target.rawUserInfo != null) return null
    val root = base.rawPath.orEmpty()
    val path = target.rawPath.orEmpty()
    return if (path == root || path.startsWith("$root/")) target else null
  }

  /** A scan for the key in a binary answer; none when the resource has no key. */
  private fun scanForKey(): KeyScan? =
      (credential as? OpenAiCredential.Key)?.let { KeyScan(it.value.encodeToByteArray()) }

  private fun echoed() = ResourceOperationFailure(ResourceFailure.SECRET_IN_RESPONSE)

  private fun millisSince(start: Long): Long = (System.nanoTime() - start) / 1_000_000

  /** The tokens the answer says were used, if it says; whatever is not a number is left out. */
  private fun usageOf(text: String): OpenAiTokenUsage? {
    val usage =
        try {
          (Json.parseToJsonElement(text) as? JsonObject)?.get("usage") as? JsonObject
        } catch (e: SerializationException) {
          null
        } ?: return null
    fun count(name: String) = (usage[name] as? JsonPrimitive)?.longOrNull
    return OpenAiTokenUsage(
        count("prompt_tokens"),
        count("completion_tokens"),
        count("total_tokens"),
    )
  }

  private fun portOf(uri: URI): Int =
      if (uri.port != -1) uri.port else if (uri.scheme.equals("https", true)) 443 else 80

  private fun blocked(status: Int) =
      ResourceOperationFailure(ResourceFailure.REDIRECT_BLOCKED, null, status)

  private fun categoryOf(status: Int): ResourceFailure =
      when {
        status == 401 || status == 403 -> ResourceFailure.DENIED
        status == 429 -> ResourceFailure.RATE_LIMITED
        status >= 500 -> ResourceFailure.SERVER_ERROR
        else -> ResourceFailure.REQUEST_REJECTED
      }

  /** Lower case names, no credential by name, and no echo of the key in what is left. */
  private fun answerHeaders(headers: Map<String, List<String>>): Map<String, List<String>> {
    val key = (credential as? OpenAiCredential.Key)?.value
    val kept = LinkedHashMap<String, List<String>>()
    for ((name, values) in headers) {
      if (name.startsWith(":") || HeaderRules.isCredentialName(name)) continue
      kept[name.lowercase()] = values.map { if (key != null) it.replace(key, "***") else it }
    }
    return kept
  }

  companion object {
    const val OPERATION = "openai.call"
    const val DOWNLOAD = "openai.download"
    const val STREAM_OPEN = "openai.stream.open"
    const val STREAM_NEXT = "openai.stream.next"
    const val STREAM_CLOSE = "openai.stream.close"
    private val REDIRECTS = setOf(301, 302, 303, 307, 308)
    private const val MAX_REDIRECTS = 5
    private const val BUFFER = 16 * 1024

    /**
     * The timers that stop a call: one thread for all of them, which only ever flips a switch. It
     * is shared by every run, and the first call of any run makes it (WI-66).
     */
    private val TIMERS =
        ScheduledThreadPoolExecutor(1, SharedThreads.factory("openai-timers")).apply {
          removeOnCancelPolicy = true
        }
  }
}

/** Why a stream is over: null when it ended well. */
private class Ending(val failure: ResourceFailure?)
