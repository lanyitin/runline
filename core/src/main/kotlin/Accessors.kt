package dev.lawlan.runline.core

import java.util.function.Function

/** The accessors of the typed shared resources a pipeline declared (ADR-019). */
interface Accessors {
  fun file(name: String): FileAccessor
}

/** Controlled access to the one file behind a `file` resource; the path is never exposed. */
interface FileAccessor {
  fun readText(): String

  fun writeText(text: String)
}

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

  /** The file the resource names does not exist (yet). */
  NOT_FOUND,

  /** The host could not carry the operation out and says no more than that. */
  FAILED,
}

/** Thrown by an accessor; carries the failure category and never a path or system message. */
class ResourceAccessException(
    val resource: String,
    val failure: ResourceFailure,
    val errorId: String? = null,
) : RuntimeException("Shared resource '$resource': $failure")

/**
 * What the host (Engine or development entry) lends a run for its typed resources: the names it
 * provided accessors for with their types, and the single call that executes an operation on the
 * host's side. Only JDK types cross it.
 */
class ResourceLink(
    val provided: Map<String, String>,
    val call: Function<Map<String, Any?>, Map<String, Any?>>,
)
