package dev.lawlan.runline.engine.traceexport

import dev.lawlan.runline.accessors.tls.TlsContext
import dev.lawlan.runline.engine.config.TraceExportTuning
import dev.lawlan.runline.engine.secret.SecretLookup
import dev.lawlan.runline.engine.secret.SecretStore
import io.opentelemetry.api.common.AttributeKey as OtelKey
import io.opentelemetry.api.metrics.MeterProvider
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.common.InstrumentationScopeInfo
import io.opentelemetry.sdk.resources.Resource
import java.time.Duration
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Where the spans of a run go: a queue, or nowhere when the resource cannot export. */
internal interface SpanSink {
  /** Takes [span] and returns at once; the reason it was not taken, or null when it was. */
  fun offer(span: PendingSpan): DropReason?

  /** Asks for what is queued to be sent now, without waiting for the schedule; returns at once. */
  fun requestFlush()
}

/** The sink of a resource that cannot export (its certificates cannot be used): nothing is kept. */
internal object UnavailableSink : SpanSink {
  override fun offer(span: PendingSpan): DropReason = DropReason.UNAVAILABLE

  override fun requestFlush() {}
}

/**
 * The exporter of one `trace-export` resource (ADR-46 decision 3): a bounded queue that takes spans
 * without waiting for anything, and one thread that sends them in batches over OTLP/HTTP with the
 * OpenTelemetry exporter. OpenTelemetry's exporter is created here and nowhere else sets one up: it
 * has nothing of the Engine's own telemetry in it (no global, no environment, no meter), and the
 * credential is read from the keystore whenever a batch goes out, so a reloaded keystore is enough
 * to rotate it.
 *
 * Something that goes wrong with a batch costs that batch, which is counted and warned about; the
 * batch is not tried again.
 */
internal class TraceExporter(
    private val resourceName: String,
    settings: TraceExportSettings,
    private val secretAlias: String?,
    tls: TlsContext?,
    private val secrets: SecretStore,
    private val tuning: TraceExportTuning,
    private val telemetry: TraceExportTelemetry,
) : SpanSink {
  private val credential = AtomicReference<String?>()
  private val exporter: OtlpHttpSpanExporter =
      OtlpHttpSpanExporter.builder()
          .setEndpoint(settings.endpoint.toString())
          .setTimeout(tuning.exportTimeout)
          .setConnectTimeout(minOf(tuning.exportTimeout, Duration.ofSeconds(10)))
          .setHeaders {
            credential.get()?.let { mapOf("Authorization" to it) } ?: emptyMap<String, String>()
          }
          // A batch that fails is dropped, not tried again (ADR-46 decision 4).
          .setRetryPolicy(null)
          .setMeterProvider(MeterProvider.noop())
          // Its own sender, which does not follow redirects.
          .setComponentLoader(NoRedirectHttpSender.Components)
          .also { builder -> tls?.let { builder.setSslContext(it.sslContext, it.trustManager) } }
          .build()
  private val resource: Resource =
      Resource.create(
          io.opentelemetry.api.common.Attributes.of(
              OtelKey.stringKey("service.name"),
              settings.serviceName,
          )
      )
  private val scope = InstrumentationScopeInfo.create("runline.pipeline")

  private val lock = ReentrantLock()
  private val wake = lock.newCondition()
  private val queue = ArrayDeque<PendingSpan>()
  private var closing = false
  private var flushRequested = false
  private var stopping = false
  private val worker =
      Thread.ofPlatform().name("trace-export-$resourceName").daemon(true).start(::run)

  override fun offer(span: PendingSpan): DropReason? = lock.withLock {
    if (stopping || queue.size >= tuning.queueSize) return DropReason.QUEUE_FULL
    queue.addLast(span)
    // The worker is woken to start the clock of the first span or to send a full batch.
    if (queue.size == 1 || queue.size >= tuning.batchSize) wake.signal()
    null
  }

  override fun requestFlush() {
    lock.withLock {
      flushRequested = true
      wake.signalAll()
    }
  }

  /**
   * The last run that held the resource has ended: what is queued is sent now, and the thread ends
   * when the queue is empty. Nobody waits for it; [TraceExporters] calls [shutdown] if it takes
   * longer than the wait allows.
   */
  fun endOfUse() {
    lock.withLock {
      closing = true
      flushRequested = true
      wake.signalAll()
    }
  }

  /**
   * Stops the exporter: nothing more is taken, what is still queued is dropped and counted, and the
   * thread ends once the request it may be in the middle of is over (it is not waited for).
   */
  fun shutdown() {
    val left = lock.withLock {
      stopping = true
      val n = queue.size
      queue.clear()
      wake.signalAll()
      n
    }
    if (left > 0) telemetry.dropped(resourceName, left, DropReason.ABANDONED)
    worker.interrupt()
    exporter.shutdown()
  }

  private fun run() {
    try {
      loop()
    } finally {
      exporter.shutdown()
    }
  }

  private fun loop() {
    while (true) {
      val batch = nextBatch() ?: return
      send(batch)
      lock.withLock { if (queue.isEmpty()) flushRequested = false }
    }
  }

  /** The next batch to send, waiting for it; null once the exporter is stopped. */
  private fun nextBatch(): List<PendingSpan>? {
    lock.withLock {
      var firstWaitedSince = 0L
      while (true) {
        if (stopping) return null
        if (queue.isEmpty()) {
          if (closing) return null
          firstWaitedSince = 0L
          try {
            wake.await()
          } catch (e: InterruptedException) {
            return null
          }
          continue
        }
        if (firstWaitedSince == 0L) firstWaitedSince = System.nanoTime()
        val remaining = tuning.scheduleDelay.toNanos() - (System.nanoTime() - firstWaitedSince)
        if (flushRequested || queue.size >= tuning.batchSize || remaining <= 0) break
        try {
          wake.awaitNanos(remaining)
        } catch (e: InterruptedException) {
          return null
        }
      }
      val batch = ArrayList<PendingSpan>(minOf(queue.size, tuning.batchSize))
      while (batch.size < tuning.batchSize && queue.isNotEmpty()) batch += queue.removeFirst()
      return batch
    }
  }

  /** Sends [batch]; true when it was taken. Whatever goes wrong costs the batch. */
  private fun send(batch: List<PendingSpan>): Boolean {
    val key = secretAlias?.let { secrets.lookup(it) as? SecretLookup.Found }?.value?.reveal()
    if (key == null) {
      telemetry.dropped(resourceName, batch.size, DropReason.KEY_UNAVAILABLE)
      return false
    }
    credential.set(key)
    val ok =
        try {
          val result = exporter.export(batch.map { ExportedSpan(it, resource, scope) })
          result.join(tuning.exportTimeout.toMillis() + GUARD_MILLIS, TimeUnit.MILLISECONDS)
          result.isDone && result.isSuccess
        } catch (e: Exception) {
          false
        } finally {
          credential.set(null)
        }
    if (ok) telemetry.exported(resourceName, batch.size)
    else telemetry.dropped(resourceName, batch.size, DropReason.EXPORT_FAILED)
    return ok
  }

  private companion object {
    /** Past the exporter's own timeout, so that its answer is what is waited for. */
    const val GUARD_MILLIS = 1_000L
  }
}
