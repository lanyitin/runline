package dev.lawlan.runline.core

import java.time.Duration
import java.util.function.Function

/** The accessors of the typed shared resources a pipeline declared (ADR-019). */
interface Accessors {
  fun file(name: String): FileAccessor

  fun openAiCompatible(name: String): OpenAiAccessor
}

/** Controlled access to the one file behind a `file` resource; the path is never exposed. */
interface FileAccessor {
  /**
   * The whole file as UTF-8 text; fails with [ResourceFailure.TOO_LARGE] beyond the host's limit.
   */
  fun readText(): String

  fun readBytes(): ByteArray

  /** Replaces the content of the file, making the file if it is not there. */
  fun writeText(text: String)

  fun writeBytes(bytes: ByteArray)

  /** Adds to the end of the file, making the file if it is not there. */
  fun appendText(text: String)

  fun appendBytes(bytes: ByteArray)
}

/**
 * Controlled access to an OpenAI compatible service behind an `openai-compatible` resource. The
 * address, the key, the headers and the path of every request are the resource's, never the
 * pipeline's: a pipeline names an entry of the endpoint catalog and gives what that entry takes.
 */
interface OpenAiAccessor {
  /**
   * Sends one request and returns the whole answer. Fails with [ResourceAccessException] whose
   * category says what went wrong (a refused request, a timeout, a status from the service) and
   * whose [ResourceAccessException.status] is the HTTP status when the service answered with one.
   * The Engine does not retry; whether to retry is the pipeline's decision.
   */
  fun call(request: OpenAiRequest): OpenAiResponse

  /**
   * Sends one request for a streamed answer (the entries that stream: chat and text completions,
   * responses) and returns once the service has begun to answer; the pipeline then pulls the events
   * one at a time. The stream is the run's share of requests until it ends, is closed, or the run
   * ends: read it to its end or close it before sending another request. The Engine does not retry,
   * and `stream` is the resource's to set: a body that sets it is refused.
   */
  fun stream(request: OpenAiRequest): OpenAiStream
}

/**
 * A streamed answer, pulled by the pipeline's own thread. Only JDK types come out of it. Closing it
 * (also by try-with-resources) drops the connection and gives the share of requests back.
 */
interface OpenAiStream : AutoCloseable {
  /** The HTTP status the service answered with (always a 2xx: anything else is a failure). */
  val status: Int

  /** The headers of the answer, lower case, without credentials. */
  val headers: Map<String, List<String>>

  /**
   * The data of the next event as text, waiting for it; null once the stream has ended (the service
   * said so, or closed it cleanly). A stream that breaks off, times out (first byte, idle or total)
   * or is cancelled fails with a [ResourceAccessException] of the category; what was pulled before
   * stays with the pipeline, and the same failure comes on every later pull.
   */
  fun next(): String?

  /** Ends the stream if it has not ended; never fails, and does nothing the second time. */
  override fun close()
}

/**
 * One request to an entry of the endpoint catalog. [endpoint] is the entry's name
 * (`chat.completions`, `embeddings`, `models.list`, ...); [body] is the JSON text the entry takes,
 * if it takes one, and the resource's defaults are merged under it; [pathParameters] and [query]
 * are what the entry lists; [timeouts] can only shorten the resource's.
 */
class OpenAiRequest
@JvmOverloads
constructor(
    val endpoint: String,
    val body: String? = null,
    val pathParameters: Map<String, String> = emptyMap(),
    val query: Map<String, String> = emptyMap(),
    val timeouts: OpenAiTimeouts = OpenAiTimeouts(),
)

/**
 * The limits a pipeline may ask for on one call; a limit left out is the resource's, and one that
 * is longer than the resource's is the resource's. [total] defaults to no limit unless the resource
 * has one.
 */
class OpenAiTimeouts
@JvmOverloads
constructor(
    /** Establishing the connection. */
    val connect: Duration? = null,
    /** From sending the request to the first byte of the answer. */
    val firstByte: Duration? = null,
    /** Between two reads of the answer. */
    val idle: Duration? = null,
    /** The whole call, from sending the request to the last byte of the answer. */
    val total: Duration? = null,
    /** Waiting for the run's share of requests at the service. */
    val quotaWait: Duration? = null,
)

/**
 * A complete answer of the service; header names are lower case, and no header carries a
 * credential.
 */
class OpenAiResponse(val status: Int, val headers: Map<String, List<String>>, val body: String)

/** Why an accessor refused to be used or an operation on it failed. */
enum class ResourceFailure {
  NOT_DECLARED,
  NO_TYPE_DECLARED,
  TYPE_MISMATCH,
  NOT_PROVIDED,

  /** The run that held the resource has ended; its accessors do nothing any more. */
  ENDED,

  /** An administrator released the holder by force; its accessors do nothing any more. */
  FORCE_RELEASED,

  /** The path of the file leads out of the resource root, which no operation may do. */
  PATH_REJECTED,

  /** The file is larger than a single read may return. */
  TOO_LARGE,

  /** The file the resource names does not exist (yet). */
  NOT_FOUND,

  /** The host could not carry the operation out and says no more than that. */
  FAILED,

  // The categories of `openai-compatible` (WI-46). A request the resource's rules refuse never
  // reaches the service.

  /**
   * The endpoint exists in the catalog but the administrator did not enable it for this resource.
   */
  ENDPOINT_NOT_ENABLED,

  /** The catalog has no such endpoint. */
  UNKNOWN_ENDPOINT,

  /** A path parameter, a query parameter or the body is not what the endpoint takes. */
  INVALID_ARGUMENT,

  /** The request sets a parameter the administrator locked. */
  PARAMETER_LOCKED,

  /** The model is not one the administrator allowed. */
  MODEL_NOT_ALLOWED,

  /** A numeric parameter is above the administrator's ceiling. */
  VALUE_ABOVE_LIMIT,

  /** The request asks for a streamed answer, which this call does not give. */
  STREAM_NOT_SUPPORTED,

  /** The request is larger than the resource allows. */
  REQUEST_TOO_LARGE,

  /** The answer is larger than the resource allows. */
  RESPONSE_TOO_LARGE,

  /** The resource names a key that the keystore cannot give. */
  SECRET_UNAVAILABLE,

  /** The service could not be reached. */
  CONNECTION_FAILED,
  CONNECT_TIMEOUT,

  /** No answer began within the time allowed. */
  FIRST_BYTE_TIMEOUT,

  /** The answer stopped coming for longer than the time allowed. */
  IDLE_TIMEOUT,

  /** The call as a whole took longer than the limit on it. */
  TOTAL_TIMEOUT,

  /** The run's share of requests did not become free in time. */
  QUOTA_WAIT_TIMEOUT,

  /** The service refused the credentials (401, 403). */
  DENIED,

  /** The service asked to slow down (429). */
  RATE_LIMITED,

  /** The service failed (5xx). */
  SERVER_ERROR,

  /** The service found the request wrong (any other 4xx). */
  REQUEST_REJECTED,

  /** The service redirected somewhere outside the resource's address, which is never followed. */
  REDIRECT_BLOCKED,

  /** A file written into a scope's directory would take it over the limit on what it may hold. */
  SCOPE_FULL,

  /** The call was stopped: the run was cancelled or ended, or the holder was released by force. */
  CANCELLED,
}

/** Thrown by an accessor; carries the failure category and never a path or system message. */
class ResourceAccessException
@JvmOverloads
constructor(
    val resource: String,
    val failure: ResourceFailure,
    val errorId: String? = null,
    /** The HTTP status the service answered with, for the types that talk HTTP. */
    val status: Int? = null,
) :
    RuntimeException(
        "Shared resource '$resource': $failure" + (status?.let { " (HTTP $it)" } ?: "")
    )

/**
 * What the host (Engine or development entry) lends a run for its typed resources: the names it
 * provided accessors for with their types, and the single call that executes an operation on the
 * host's side. Only JDK types cross it.
 */
class ResourceLink(
    val provided: Map<String, String>,
    val call: Function<Map<String, Any?>, Map<String, Any?>>,
)
