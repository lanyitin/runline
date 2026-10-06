package dev.lawlan.runline.engine.support

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxy
import ch.qos.logback.core.AppenderBase
import dev.lawlan.runline.engine.auth.ApiIdentity
import dev.lawlan.runline.engine.auth.Role
import dev.lawlan.runline.engine.trigger.*
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import java.time.Duration
import kotlin.test.assertIs
import org.slf4j.LoggerFactory

/**
 * Every log event of the process while it is open, with exception text included. Events arrive from
 * any thread while a test reads, so the list is guarded by one lock and readers get a snapshot.
 */
class CapturedLogs : AutoCloseable {
  private val appender = SnapshotListAppender().also { it.start() }
  private val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger

  init {
    root.addAppender(appender)
  }

  /** Each event as one text: level, logger, message, and the exception chain when there is one. */
  val lines: List<String>
    get() =
        appender.snapshot().map { event ->
          val error =
              (event.throwableProxy as? ThrowableProxy)?.throwable?.let { t ->
                generateSequence(t) { it.cause }
                    .joinToString(" <- ") { "${it::class} ${it.message}" }
              }
          "${event.level} ${event.loggerName} ${event.formattedMessage} ${error.orEmpty()}"
        }

  fun at(level: Level) =
      appender.snapshot().filter { it.level == level }.map { it.formattedMessage }

  override fun close() {
    root.detachAppender(appender)
  }
}

/** Collects events under a lock; [snapshot] copies them under the same lock. */
class SnapshotListAppender : AppenderBase<ILoggingEvent>() {
  private val events = ArrayList<ILoggingEvent>()

  override fun append(event: ILoggingEvent) {
    synchronized(events) { events.add(event) }
  }

  fun snapshot(): List<ILoggingEvent> = synchronized(events) { ArrayList(events) }
}

/**
 * Triggers on top of the real run machinery: the real run service and Runner, a real PostgreSQL
 * database, and OpenTelemetry's in-memory exporters to read what was recorded. [clock] is the clock
 * of everything about triggers; the runs themselves use the system clock.
 */
class TriggerRig(
    maxConcurrent: Int = 2,
    val clock: MutableClock = MutableClock(),
) : AutoCloseable {
  val spans: InMemorySpanExporter = InMemorySpanExporter.create()
  val metrics: InMemoryMetricReader = InMemoryMetricReader.create()
  private val otel =
      OpenTelemetrySdk.builder()
          .setTracerProvider(
              SdkTracerProvider.builder()
                  .addSpanProcessor(SimpleSpanProcessor.create(spans))
                  .build()
          )
          .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metrics).build())
          .build()
  val harness = RunHarness(maxConcurrent = maxConcurrent, openTelemetry = otel)
  val store = PostgresTriggerStore(harness.dataSource)
  val admin = TriggerAdmin(harness.definitions, store, clock)
  val catalog = TriggerCatalog(harness.definitions, store)
  val telemetry = TriggerTelemetry(otel)
  val firer = TriggerFirer(harness.service, store, telemetry)
  val root = ApiIdentity("root", Role.ADMIN)

  /** A cron trigger bound to [pipeline] of the version [hash]; it must be accepted. */
  fun cron(
      name: String,
      hash: String,
      pipeline: String,
      expression: String = "* * * * *",
      zone: String? = null,
      parameters: Map<String, String> = emptyMap(),
      enabled: Boolean = true,
  ): Trigger =
      assertIs<CreateTriggerResult.Created>(
              admin.create(
                  CreateTrigger(
                      name,
                      TriggerKind.CRON,
                      hash,
                      pipeline,
                      parameters,
                      expression,
                      zone,
                      enabled,
                  ),
                  root,
              )
          )
          .trigger

  /** A webhook trigger; returns it with its secret. */
  fun webhook(
      name: String,
      hash: String,
      pipeline: String,
      parameters: Map<String, String> = emptyMap(),
      enabled: Boolean = true,
  ): Pair<Trigger, String> {
    val created =
        assertIs<CreateTriggerResult.Created>(
            admin.create(
                CreateTrigger(
                    name,
                    TriggerKind.WEBHOOK,
                    hash,
                    pipeline,
                    parameters,
                    enabled = enabled,
                ),
                root,
            )
        )
    return created.trigger to checkNotNull(created.secret)
  }

  /**
   * The finished span called [name]. A run's state is stored before its span ends, so a test that
   * has seen the state must still wait for the span.
   */
  fun awaitSpan(name: String, seconds: Long = 10): SpanData {
    val deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos()
    while (true) {
      spans.finishedSpanItems
          .singleOrNull { it.name == name }
          ?.let {
            return it
          }
      check(System.nanoTime() < deadline) { "no span $name finished" }
      Thread.sleep(10)
    }
  }

  /** The counter [name] as attribute text to value, for example `trigger=a,outcome=created`. */
  fun counter(name: String): Map<String, Long> =
      metrics
          .collectAllMetrics()
          .firstOrNull { it.name == name }
          ?.longSumData
          ?.points
          .orEmpty()
          .associate {
            it.attributes
                .asMap()
                .entries
                .sortedBy { e -> e.key.key }
                .joinToString(",") { e -> "${e.key.key}=${e.value}" } to it.value
          }

  override fun close() = harness.close()
}
