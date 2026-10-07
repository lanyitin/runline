package dev.lawlan.runline.accessors.openai

import dev.lawlan.runline.accessors.ResourceBinding
import dev.lawlan.runline.accessors.ResourceOperationFailure
import dev.lawlan.runline.core.ResourceFailure
import dev.lawlan.runline.core.ResourceTypes
import java.io.ByteArrayOutputStream
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
import java.util.concurrent.ExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
) : ResourceBinding {
  override val type: String = ResourceTypes.OPENAI_COMPATIBLE

  /** One client per connect limit, which a client cannot vary by request; usually just one. */
  private val clients = ConcurrentHashMap<Long, HttpClient>()

  private fun clientFor(connectMillis: Long): HttpClient =
      clients.computeIfAbsent(connectMillis) {
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofMillis(connectMillis))
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
        STREAM_OPEN -> call(arguments, streaming = true)
        STREAM_NEXT -> streamOf(arguments).next()
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
      private val events: ServerSentEvents,
  ) {
    private val ended = AtomicBoolean()

    fun next(): String? {
      if (ended.get()) return null
      val data = events.next()
      if (data == null || data == "[DONE]") {
        end(null)
        return null
      }
      return data
    }

    /** The stream is over, whichever way: the connection goes, the share is given back. */
    fun end(failure: ResourceFailure?) {
      if (!ended.compareAndSet(false, true)) return
      runCatching { call.stream?.close() }
      call.release()
      report.finish(endpoint, failure, null)
    }
  }

  private fun call(arguments: Map<String, Any?>, streaming: Boolean): Any? {
    val endpoint =
        (arguments["endpoint"] as? String)?.let { OpenAiEndpoints.find(it)?.id } ?: "unknown"
    val report = Report()
    var result: Throwable? = null
    try {
      return run(arguments, endpoint, report, streaming)
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

    /** A stream took the call over, and tells the observer when it ends. */
    @Volatile var handedOver = false
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
          )
      runCatching { observer.finished(resource, endpoint, outcome) }
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
  ): Any? {
    if (aborted) throw ResourceOperationFailure(ResourceFailure.CANCELLED)
    return run(OpenAiRequestPlan.of(settings, arguments, streaming), endpoint, report)
  }

  private fun run(plan: OpenAiRequestPlan, endpoint: String, report: Report): Any? {
    if (aborted) throw ResourceOperationFailure(ResourceFailure.CANCELLED)
    if (credential is OpenAiCredential.Unavailable) {
      throw ResourceOperationFailure(ResourceFailure.SECRET_UNAVAILABLE)
    }
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
      return exchange(call, plan, report)
    } finally {
      if (!call.keptOpen) call.release()
    }
  }

  override fun abort() {
    aborted = true
    quota.abort()
    live.forEach { it.stop(ResourceFailure.CANCELLED) }
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

    /** A stream took the call over: it ends the call, not the exchange that opened it. */
    @Volatile var keptOpen = false
    private val released = AtomicBoolean()

    /** The call is over: its timer stops, it can no longer be cut, and its share is free again. */
    fun release() {
      if (!released.compareAndSet(false, true)) return
      total?.cancel(false)
      live -= this
      quota.release()
    }

    /** Stops the call for [reason]; the first reason stays. */
    fun stop(reason: ResourceFailure) {
      if (stoppedFor == null) stoppedFor = reason
      future?.cancel(true)
      runCatching { stream?.close() }
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
    var hops = 0
    while (true) {
      val client = clientFor(call.limits.connectMillis)
      val response = awaitHeaders(call, client, request(uri, method, body, call.limits))
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
        }
        uri = next
        continue
      }
      if (status !in 200..299) {
        response.body().close()
        throw ResourceOperationFailure(categoryOf(status), null, status)
      }
      val declared = response.headers().firstValueAsLong("content-length")
      if (declared.isPresent && declared.asLong > settings.maxResponseBytes) {
        response.body().close()
        throw ResourceOperationFailure(ResourceFailure.RESPONSE_TOO_LARGE, null, status)
      }
      if (plan.streaming) return startStream(call, response, status, report, plan.endpoint.id)
      val readStart = System.nanoTime()
      val text = readBody(call, response.body(), status)
      report.generationMillis = millisSince(readStart)
      report.usage = usageOf(text)
      return mapOf(
          "status" to status,
          "headers" to answerHeaders(response.headers().map()),
          "body" to text,
      )
    }
  }

  private fun startStream(
      call: Call,
      response: HttpResponse<InputStream>,
      status: Int,
      report: Report,
      endpoint: String,
  ): Map<String, Any?> {
    val input = response.body()
    val id = streamIds.incrementAndGet()
    streams[id] = OpenStream(call, report, endpoint, ServerSentEvents { input.read(it) })
    call.keptOpen = true
    report.handedOver = true
    return mapOf(
        "stream" to id,
        "status" to status,
        "headers" to answerHeaders(response.headers().map()),
    )
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
  private fun readBody(call: Call, stream: InputStream, status: Int): String {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(BUFFER)
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
      if (out.size() + read > settings.maxResponseBytes) {
        runCatching { stream.close() }
        throw ResourceOperationFailure(ResourceFailure.RESPONSE_TOO_LARGE, null, status)
      }
      out.write(buffer, 0, read)
    }
    return out.toString(StandardCharsets.UTF_8)
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
      limits: OpenAiLimits,
  ): HttpRequest {
    val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(limits.firstByteMillis))
    builder.header("Accept", "application/json")
    if (body != null) builder.header("Content-Type", "application/json")
    (credential as? OpenAiCredential.Key)?.let {
      builder.header("Authorization", "Bearer ${it.value}")
    }
    settings.organization?.let { builder.header("OpenAI-Organization", it) }
    settings.project?.let { builder.header("OpenAI-Project", it) }
    settings.headers.forEach { (name, value) -> builder.header(name, value) }
    builder.method(
        method,
        if (body == null) HttpRequest.BodyPublishers.noBody()
        else HttpRequest.BodyPublishers.ofByteArray(body),
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
    const val STREAM_OPEN = "openai.stream.open"
    const val STREAM_NEXT = "openai.stream.next"
    private val REDIRECTS = setOf(301, 302, 303, 307, 308)
    private const val MAX_REDIRECTS = 5
    private const val BUFFER = 16 * 1024

    /** The timers that stop a call: one thread for all of them, which only ever flips a switch. */
    private val TIMERS =
        ScheduledThreadPoolExecutor(1) { task ->
              Thread.ofPlatform().name("openai-timers").daemon(true).unstarted(task)
            }
            .apply { removeOnCancelPolicy = true }
  }
}
