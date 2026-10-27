package dev.lawlan.runline.engine.config

import java.time.Duration

/**
 * How the Engine buffers and exports the spans of `trace-export` resources (ADR-46 decision 4 and
 * 12). The defaults are the scale of OpenTelemetry's batch span processor (queue 2048, batch 512, a
 * batch every 5 seconds, a request that ends after 10 seconds at the latest) and the deployment may
 * change every one of them. They are the same for every resource.
 */
data class TraceExportTuning(
    /** Spans a resource's queue holds; a span submitted to a full queue is dropped and counted. */
    val queueSize: Int = DEFAULT_QUEUE_SIZE,
    /** Spans in one export request. */
    val batchSize: Int = DEFAULT_BATCH_SIZE,
    /** How long spans wait for a fuller batch before they are exported anyway. */
    val scheduleDelay: Duration = DEFAULT_SCHEDULE_DELAY,
    /** How long one export request may take; then the batch is dropped. */
    val exportTimeout: Duration = DEFAULT_EXPORT_TIMEOUT,
    /** How long the end of a run waits for the queue to be flushed; then the wait is given up. */
    val flushWait: Duration = DEFAULT_FLUSH_WAIT,
    /** The most attributes of a span that are kept; the rest are dropped and counted. */
    val maxAttributes: Int = DEFAULT_MAX_ATTRIBUTES,
    /** The most characters of a text value that are kept; the rest is cut off and counted. */
    val maxAttributeLength: Int = DEFAULT_MAX_ATTRIBUTE_LENGTH,
) {
  companion object {
    const val DEFAULT_QUEUE_SIZE = 2048
    const val DEFAULT_BATCH_SIZE = 512
    val DEFAULT_SCHEDULE_DELAY: Duration = Duration.ofSeconds(5)
    val DEFAULT_EXPORT_TIMEOUT: Duration = Duration.ofSeconds(10)
    val DEFAULT_FLUSH_WAIT: Duration = Duration.ofSeconds(5)
    const val DEFAULT_MAX_ATTRIBUTES = 128
    const val DEFAULT_MAX_ATTRIBUTE_LENGTH = 8192
  }
}
