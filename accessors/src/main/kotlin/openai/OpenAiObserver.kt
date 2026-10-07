package dev.lawlan.runline.accessors.openai

import dev.lawlan.runline.core.ResourceFailure

/** The tokens a service says a call used, when its answer says so; each part may be missing. */
data class OpenAiTokenUsage(val prompt: Long?, val completion: Long?, val total: Long?)

/**
 * How one call of a pipeline ended, in the numbers the host keeps (ADR-019 decision 10). Nothing of
 * the request or the answer is in it: no model, prompt, address or body.
 */
data class OpenAiOutcome(
    /** Null when the call succeeded. */
    val failure: ResourceFailure?,
    val status: Int?,
    /** Whether a request went out; false for one the rules refused or that got no share in time. */
    val sent: Boolean,
    /** From the call to the moment it had its share of requests. */
    val quotaWaitMillis: Long,
    /** From having the share to the end of the call; 0 when no request went out. */
    val latencyMillis: Long,
    /** From sending the request to the first byte of the answer, when there was one. */
    val firstByteMillis: Long?,
    /** From the first byte to the last, when the whole answer came. */
    val generationMillis: Long?,
    val usage: OpenAiTokenUsage?,
    /** For a stream: the longest time spent waiting for the next event, the first one included. */
    val maxChunkGapMillis: Long? = null,
)

/** What the host (the Engine's metrics and traces) learns of the calls on a binding. */
interface OpenAiObserver {
  /** A request is about to go out: it has its share of requests. */
  fun started(resource: String, endpoint: String) {}

  /** The call ended, whichever way; [endpoint] is the catalog entry or `unknown`. */
  fun finished(resource: String, endpoint: String, outcome: OpenAiOutcome) {}

  /**
   * A stream is about to open, in the operation that opens it: whatever is returned comes back to
   * [streamFinished] when the stream is over, which can be in another operation or thread. It is
   * what a trace needs to give a stream a span of its own for as long as it lasts.
   */
  fun streamStarted(resource: String, endpoint: String): Any? = null

  /** The stream whose [streamStarted] gave [handle] is over, with how it went. */
  fun streamFinished(handle: Any?, resource: String, endpoint: String, outcome: OpenAiOutcome) {}

  companion object {
    val NONE: OpenAiObserver = object : OpenAiObserver {}
  }
}
