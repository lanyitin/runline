package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.accessors.openai.OpenAiObserver
import dev.lawlan.runline.accessors.openai.OpenAiOutcome
import dev.lawlan.runline.core.ResourceFailure
import dev.lawlan.runline.core.ResourceTypes
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context

/**
 * What the Engine records of the calls on `openai-compatible` resources (ADR-019 decision 10,
 * WI-46): the requests in flight, the requests by outcome, how long they took and how long they
 * waited for their share, how long the service generated, the timeouts by kind and the tokens it
 * says it used; and, on the span of the request in the run's trace, the time it waited and the time
 * to the first byte. Every label is the resource's name, its type, an entry of the endpoint
 * catalog, an outcome or a kind of timeout: a fixed set, never a model, a prompt or an address.
 * Nothing of a request or an answer is recorded.
 */
class OpenAiTelemetry(openTelemetry: OpenTelemetry, private val usage: OpenAiUsage) :
    OpenAiObserver {
  private val meter = openTelemetry.getMeter("runline.resources.openai")
  private val tracer = openTelemetry.getTracer("runline.resources.openai")
  private val inFlight =
      meter
          .upDownCounterBuilder("runline.resources.openai.requests.in_flight")
          .setDescription("Requests of runs to the service that have gone out and are not answered")
          .build()
  private val requests =
      meter
          .counterBuilder("runline.resources.openai.requests")
          .setDescription("Calls by endpoint of the catalog and outcome")
          .build()
  private val duration =
      meter
          .histogramBuilder("runline.resources.openai.request.duration")
          .setUnit("s")
          .setDescription("From having a share of requests to the end of the call")
          .build()
  private val quotaWait =
      meter
          .histogramBuilder("runline.resources.openai.quota_wait.duration")
          .setUnit("s")
          .setDescription("Time a call waited for its run's share of requests")
          .build()
  private val generation =
      meter
          .histogramBuilder("runline.resources.openai.generation.duration")
          .setUnit("s")
          .setDescription("From the first byte of an answer to its last")
          .build()
  private val firstChunk =
      meter
          .histogramBuilder("runline.resources.openai.stream.first_chunk.duration")
          .setUnit("s")
          .setDescription("From sending a streamed request to the first byte of its answer")
          .build()
  private val maxGap =
      meter
          .histogramBuilder("runline.resources.openai.stream.max_gap.duration")
          .setUnit("s")
          .setDescription("The longest wait for the next event of a stream")
          .build()
  private val timeouts =
      meter
          .counterBuilder("runline.resources.openai.timeouts")
          .setDescription("Calls that ended in a timeout, by kind")
          .build()
  private val tokens =
      meter
          .counterBuilder("runline.resources.openai.tokens")
          .setDescription("Tokens the service reported for its answers, by kind")
          .build()

  override fun started(resource: String, endpoint: String) {
    usage.started(resource)
    inFlight.add(1, attributes(resource))
  }

  override fun finished(resource: String, endpoint: String, outcome: OpenAiOutcome) {
    if (outcome.sent) {
      usage.finished(resource)
      inFlight.add(-1, attributes(resource))
    }
    val label = outcome.failure?.name?.lowercase() ?: "ok"
    val base = attributes(resource)
    val byEndpoint = attributes(resource, ENDPOINT to endpoint)
    val byOutcome = attributes(resource, ENDPOINT to endpoint, OUTCOME to label)
    requests.add(1, byOutcome)
    if (outcome.sent) duration.record(outcome.latencyMillis / 1000.0, byOutcome)
    if (outcome.sent || outcome.failure == ResourceFailure.QUOTA_WAIT_TIMEOUT) {
      quotaWait.record(outcome.quotaWaitMillis / 1000.0, base)
    }
    outcome.generationMillis?.let { generation.record(it / 1000.0, byEndpoint) }
    if (outcome.maxChunkGapMillis != null) {
      outcome.firstByteMillis?.let { firstChunk.record(it / 1000.0, byEndpoint) }
      maxGap.record(outcome.maxChunkGapMillis!! / 1000.0, byEndpoint)
    }
    outcome.failure?.let(::timeoutKind)?.let { timeouts.add(1, attributes(resource, KIND to it)) }
    outcome.usage?.prompt?.let { tokens.add(it, attributes(resource, KIND to "prompt")) }
    outcome.usage?.completion?.let { tokens.add(it, attributes(resource, KIND to "completion")) }
    // A stream has a span of its own, which is told when the stream is over.
    if (outcome.maxChunkGapMillis == null) annotate(Span.current(), endpoint, label, outcome)
  }

  override fun streamStarted(resource: String, endpoint: String): Any? {
    val parent = Span.current()
    if (!parent.spanContext.isValid) return null
    return tracer
        .spanBuilder("runline.resource.openai.stream")
        .setParent(Context.current())
        .setAttribute("runline.resource.name", resource)
        .setAttribute("runline.resource.type", ResourceTypes.OPENAI_COMPATIBLE)
        .startSpan()
  }

  override fun streamFinished(
      handle: Any?,
      resource: String,
      endpoint: String,
      outcome: OpenAiOutcome,
  ) {
    val span = handle as? Span ?: return
    annotate(span, endpoint, outcome.failure?.name?.lowercase() ?: "ok", outcome)
    outcome.maxChunkGapMillis?.let {
      span.setAttribute("runline.resource.openai.max_chunk_gap_ms", it)
    }
    outcome.generationMillis?.let { span.setAttribute("runline.resource.openai.generation_ms", it) }
    span.end()
  }

  /** The span of the request, if the call runs inside one. */
  private fun annotate(span: Span, endpoint: String, label: String, outcome: OpenAiOutcome) {
    if (!span.spanContext.isValid) return
    span.setAttribute("runline.resource.openai.endpoint", endpoint)
    span.setAttribute("runline.resource.openai.outcome", label)
    span.setAttribute("runline.resource.openai.quota_wait_ms", outcome.quotaWaitMillis)
    outcome.firstByteMillis?.let { span.setAttribute("runline.resource.openai.first_byte_ms", it) }
    if (outcome.failure != null) span.setStatus(StatusCode.ERROR)
  }

  private fun timeoutKind(failure: ResourceFailure): String? =
      when (failure) {
        ResourceFailure.CONNECT_TIMEOUT -> "connect"
        ResourceFailure.QUOTA_WAIT_TIMEOUT -> "quota_wait"
        ResourceFailure.FIRST_BYTE_TIMEOUT -> "first_byte"
        ResourceFailure.IDLE_TIMEOUT -> "idle"
        ResourceFailure.TOTAL_TIMEOUT -> "total"
        else -> null
      }

  private fun attributes(
      resource: String,
      vararg more: Pair<AttributeKey<String>, String>,
  ): Attributes {
    val builder =
        Attributes.builder().put(RESOURCE, resource).put(TYPE, ResourceTypes.OPENAI_COMPATIBLE)
    more.forEach { (key, value) -> builder.put(key, value) }
    return builder.build()
  }

  private companion object {
    val RESOURCE: AttributeKey<String> = AttributeKey.stringKey("resource")
    val TYPE: AttributeKey<String> = AttributeKey.stringKey("type")
    val ENDPOINT: AttributeKey<String> = AttributeKey.stringKey("endpoint")
    val OUTCOME: AttributeKey<String> = AttributeKey.stringKey("outcome")
    val KIND: AttributeKey<String> = AttributeKey.stringKey("kind")
  }
}
