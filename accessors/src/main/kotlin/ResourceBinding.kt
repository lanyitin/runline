package dev.lawlan.runline.accessors

import dev.lawlan.runline.core.ResourceFailure

/** The host's side of one accessor: the entity behind it and the operations it offers. */
interface ResourceBinding {
  /** The resource type, as in a pipeline's declaration (`file`). */
  val type: String

  /** Executes [operation] with JDK-typed [arguments]; fails with [ResourceOperationFailure]. */
  fun execute(operation: String, arguments: Map<String, Any?>): Any?

  /**
   * Asks whatever [execute] is doing right now to stop early (a request, a query), because the
   * binding is about to be invalidated. Called from another thread; instant operations need not do
   * anything.
   */
  fun abort() {}

  /**
   * Lets go of what the binding holds (a client, connections), once, when it is invalidated and
   * nothing is running on it any more. The entity itself is not closed: it is the resource's.
   */
  fun close() {}
}

/**
 * An operation failed; [failure] and, for the types that talk HTTP, the [status] the service
 * answered with, and for those that talk SQL, the [sqlState], are the only things a run is told
 * about it. The [cause] is for the host's log. A failure that [withErrorId] is given an errorId
 * under which the host logs the cause, whatever its category; any other gets one only when it is a
 * plain failure.
 */
class ResourceOperationFailure(
    val failure: ResourceFailure,
    cause: Throwable? = null,
    val status: Int? = null,
    val sqlState: String? = null,
    val withErrorId: Boolean = false,
) : RuntimeException(failure.name, cause)
