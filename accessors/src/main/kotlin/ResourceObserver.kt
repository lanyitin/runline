package dev.lawlan.runline.accessors

import dev.lawlan.runline.core.ResourceFailure

/**
 * What the host learns about the operations on its accessors: where it traces, counts and logs
 * them. Nothing here is passed on to the run.
 */
interface ResourceObserver {
  /** Wraps one operation; [operation] is its category (`file.read`), never a path or content. */
  fun <T> operation(resource: String, type: String, operation: String, body: () -> T): T = body()

  /** An operation failed. [cause] is the original exception, for the host's log only. */
  fun failed(
      resource: String,
      type: String,
      operation: String,
      failure: ResourceFailure,
      errorId: String?,
      cause: Throwable?,
  ) {}

  /** The accessors of [resource] were invalidated, for the reason. Called once per resource. */
  fun invalidated(resource: String, type: String, reason: Invalidation) {}

  companion object {
    val NONE: ResourceObserver = object : ResourceObserver {}
  }
}
