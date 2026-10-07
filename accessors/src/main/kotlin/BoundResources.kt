package dev.lawlan.runline.accessors

import dev.lawlan.runline.core.ResourceFailure
import dev.lawlan.runline.runner.ResourceHost
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * The accessors of one run, by resource name. Fail closed: once [invalidate] (or [invalidateAll])
 * has returned, every later call on that resource fails with the reason before the entity is
 * touched, and no operation that was already running can still complete. Operations on a resource
 * run under its read lock and an invalidation takes the write lock (after asking the binding to
 * [ResourceBinding.abort] what it is doing, so a long operation cannot hold the invalidation up),
 * so the two never overlap. The reason is kept per resource and per run, not per call.
 */
class BoundResources(
    private val bindings: Map<String, ResourceBinding>,
    private val observer: ResourceObserver = ResourceObserver.NONE,
) : ResourceHost {
  override val provided: Map<String, String> = bindings.mapValues { it.value.type }

  private class State {
    val lock = ReentrantReadWriteLock()

    /** Set once, before anything is aborted, so that no call that comes later gets through. */
    val claimed = AtomicReference<Invalidation?>()

    val invalidation: Invalidation?
      get() = claimed.get()
  }

  private val states = bindings.keys.associateWith { State() }

  @Volatile private var runInvalidation: Invalidation? = null

  /** Where the run's directories are, once the Runner has said; see [workspaceReady]. */
  @Volatile private var directories: WorkspaceDirectories? = null

  override fun workspaceReady(
      sharedDir: Path,
      runDir: Path,
      maxBytesPerScope: Long,
      writable: Map<String, Boolean>,
  ) {
    directories = WorkspaceDirectories(sharedDir, runDir, maxBytesPerScope, writable)
  }

  override fun call(request: Map<String, Any?>): Map<String, Any?> {
    val name = request["resource"]
    val binding = bindings[name] ?: return failure(ResourceFailure.NOT_PROVIDED)
    val state = states.getValue(name as String)
    @Suppress("UNCHECKED_CAST") val given = request["arguments"] as Map<String, Any?>
    return state.lock.read {
      val revoked = state.invalidation ?: runInvalidation
      if (revoked != null) return@read failure(revoked.failure)
      val arguments =
          try {
            withDirectories(given)
          } catch (e: ResourceOperationFailure) {
            return@read failed(name, binding, request["operation"] as String, e.failure, e.cause)
          }
      execute(name, binding, request["operation"] as String, arguments)
    }
  }

  /**
   * What a run says about files is a scope and a relative path. Where the scope is, and how much it
   * may hold, comes from here: from what the Runner told, never from the call.
   */
  private fun withDirectories(arguments: Map<String, Any?>): Map<String, Any?> {
    if (arguments["files"] == null && arguments["target"] == null) return arguments
    val known = directories ?: throw ResourceOperationFailure(ResourceFailure.FAILED)
    return known.trusted(arguments)
  }

  private fun execute(
      resource: String,
      binding: ResourceBinding,
      operation: String,
      arguments: Map<String, Any?>,
  ): Map<String, Any?> {
    return try {
      success(
          observer.operation(resource, binding.type, operation) {
            binding.execute(operation, arguments)
          }
      )
    } catch (e: ResourceOperationFailure) {
      failed(resource, binding, operation, e.failure, e.cause, e.status, e.sqlState, e.withErrorId)
    } catch (e: Exception) {
      failed(resource, binding, operation, ResourceFailure.FAILED, e)
    }
  }

  /** An unforeseen failure gets an errorId, which is how the log's original is found. */
  private fun failed(
      resource: String,
      binding: ResourceBinding,
      operation: String,
      failure: ResourceFailure,
      cause: Throwable?,
      status: Int? = null,
      sqlState: String? = null,
      withErrorId: Boolean = false,
  ): Map<String, Any?> {
    val errorId =
        if (failure == ResourceFailure.FAILED || withErrorId) UUID.randomUUID().toString() else null
    observer.failed(resource, binding.type, operation, failure, errorId, cause)
    return failure(failure).also { answer ->
      errorId?.let { answer["errorId"] = it }
      status?.let { answer["status"] = it }
      // Five characters of the standard's alphabet and nothing more, whatever the binding gave.
      sqlState?.takeIf { SQL_STATE.matches(it) }?.let { answer["sqlState"] = it }
    }
  }

  /**
   * From now on every operation on [resource] fails with the reason; nothing reaches the entity.
   */
  fun invalidate(resource: String, reason: Invalidation) {
    val binding = bindings[resource] ?: return
    val state = states.getValue(resource)
    // The reason is claimed first: a call that comes while the binding is being aborted is refused
    // with it, and the first reason stays.
    val first = state.claimed.compareAndSet(null, reason)
    binding.abort()
    state.lock.write {
      // Nothing is running on the binding now and nothing can start: let it go.
      if (first) runCatching { binding.close() }
    }
    if (first) observer.invalidated(resource, binding.type, reason)
  }

  /** [invalidate] for every resource of the run, and for any that is looked at later. */
  fun invalidateAll(reason: Invalidation) {
    if (runInvalidation == null) runInvalidation = reason
    bindings.keys.forEach { invalidate(it, reason) }
  }

  private companion object {
    val SQL_STATE = Regex("[0-9A-Z]{5}")
  }

  private fun success(value: Any?) =
      java.util.HashMap<String, Any?>().apply {
        put("ok", true)
        put("value", value)
      }

  private fun failure(failure: ResourceFailure): java.util.HashMap<String, Any?> =
      java.util.HashMap<String, Any?>().apply {
        put("ok", false)
        put("failure", failure.name)
      }
}
