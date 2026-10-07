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
}

/** An operation failed; [failure] is the only thing a run is told about it. */
class ResourceOperationFailure(val failure: ResourceFailure, cause: Throwable? = null) :
    RuntimeException(failure.name, cause)
