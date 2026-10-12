package dev.lawlan.runline.engine.view

import dev.lawlan.runline.accessors.ReadOnlyException
import dev.lawlan.runline.accessors.ReadOnlyFailure
import dev.lawlan.runline.accessors.ReadOnlyQuery
import dev.lawlan.runline.accessors.ReadOnlyRead
import dev.lawlan.runline.accessors.ReadOnlyRows
import dev.lawlan.runline.accessors.ReadOrder
import dev.lawlan.runline.engine.artifact.StoredDefinition
import dev.lawlan.runline.engine.artifact.VersionOutcome
import dev.lawlan.runline.engine.artifact.ViewDoc
import dev.lawlan.runline.engine.auth.ApiIdentity
import dev.lawlan.runline.engine.resource.ResourceBehaviors
import dev.lawlan.runline.engine.resource.ResourceProblemKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** What a request for the rows of a view gave: the values of its parameters, and the page. */
class ViewRequest(
    /** Values by parameter name, as the request gave them. */
    val parameters: Map<String, List<String>>,
    val limit: String?,
    val offset: String?,
)

sealed interface ViewReadResult {
  class Page(
      val view: ViewDoc,
      val rows: List<List<Any?>>,
      val hasMore: Boolean,
      val limit: Int,
      val offset: Long,
  ) : ViewReadResult

  data class Ambiguous(val uploaders: List<String>) : ViewReadResult

  /** No such version and pipeline, or one the caller may not see: never told apart. */
  data object DefinitionNotFound : ViewReadResult

  data object ViewNotFound : ViewReadResult

  class InvalidParameter(val refusal: ParameterRefusal) : ViewReadResult

  /** The view's role cannot be used, for the reason a run would be refused with. */
  class ResourceUnavailable(val role: String, val problem: ResourceProblemKind) : ViewReadResult

  /** The resource already has as many reads going as it allows. */
  data object Busy : ViewReadResult

  data object QueryRejected : ViewReadResult

  data object SchemaMismatch : ViewReadResult

  data object TooLarge : ViewReadResult

  data object Timeout : ViewReadResult

  data object ConnectionFailed : ViewReadResult

  /** Nothing is known of it but the SQLState and the kind of exception: no words, no values. */
  class Failed(val sqlState: String?, val causeClass: String?) : ViewReadResult
}

/**
 * Reads the rows of a view (ADR-033): finds the version as for bindings, checks the request against
 * the declaration, and has the resource of the view's role read one page, on a connection of its
 * own, within the limits of the resource. Nothing here holds the resource as a run does.
 */
class ViewReader(
    private val targets: ViewTargets,
    private val behaviors: ResourceBehaviors,
    private val gate: ViewGate,
    private val telemetry: ViewTelemetry,
) {
  suspend fun read(
      contentHash: String,
      uploader: String?,
      pipeline: String,
      viewId: String,
      request: ViewRequest,
      caller: ApiIdentity,
  ): ViewReadResult {
    val definition =
        when (val found = withIo { targets.definition(contentHash, uploader, pipeline, caller) }) {
          is VersionOutcome.Ambiguous -> return ViewReadResult.Ambiguous(found.uploaders)
          VersionOutcome.NotFound -> return ViewReadResult.DefinitionNotFound
          is VersionOutcome.Resolved -> found.value
        }
    val view =
        definition.metadata.views.firstOrNull { it.id == viewId }
            ?: return ViewReadResult.ViewNotFound
    val observation = telemetry.begin(definition.name, view.id, definition.uploadedBy, caller.name)
    try {
      val result = readPage(definition, view, request)
      observation.end(result.code, (result as? ViewReadResult.Page)?.rows?.size)
      return result
    } catch (e: Throwable) {
      observation.end(if (e is CancellationException) "cancelled" else "failed")
      throw e
    }
  }

  private suspend fun readPage(
      definition: StoredDefinition,
      view: ViewDoc,
      request: ViewRequest,
  ): ViewReadResult {
    val values =
        when (val bound = ViewParameters.bind(view, request.parameters)) {
          is ViewParameters.Result.Refused -> return ViewReadResult.InvalidParameter(bound.refusal)
          is ViewParameters.Result.Bound -> bound.values
        }
    val page =
        when (val paged = ViewParameters.page(request.limit, request.offset)) {
          is ViewParameters.PageResult.Refused ->
              return ViewReadResult.InvalidParameter(paged.refusal)
          is ViewParameters.PageResult.Page -> paged
        }
    val resource =
        when (val resolved = withIo { targets.resourcesOf(definition, listOf(view)).single() }) {
          is ViewResource.Unusable ->
              return ViewReadResult.ResourceUnavailable(view.resource, resolved.kind)
          is ViewResource.Usable -> resolved.resource
        }
    val query =
        ReadOnlyQuery(
            view.query,
            values,
            view.columns.map { it.name },
            view.sort.map { ReadOrder(it.column, it.descending) },
            page.limit,
            page.offset,
        )
    val read =
        behaviors.of(resource.type)?.readOnly(resource, query)
            ?: return ViewReadResult.ConnectionFailed
    val permit = gate.enter(resource.name) ?: return ViewReadResult.Busy
    return try {
      val rows = permit.use { execute(read) }
      ViewReadResult.Page(view, rows.rows, rows.hasMore, page.limit, page.offset)
    } catch (e: TimeoutCancellationException) {
      ViewReadResult.Timeout
    } catch (e: ReadOnlyException) {
      when (e.failure) {
        ReadOnlyFailure.QUERY_REJECTED -> ViewReadResult.QueryRejected
        ReadOnlyFailure.SCHEMA_MISMATCH -> ViewReadResult.SchemaMismatch
        ReadOnlyFailure.RESULT_TOO_LARGE -> ViewReadResult.TooLarge
        ReadOnlyFailure.CONNECTION_FAILED,
        ReadOnlyFailure.UNAVAILABLE -> ViewReadResult.ConnectionFailed
        ReadOnlyFailure.CANCELLED,
        ReadOnlyFailure.FAILED -> ViewReadResult.Failed(e.sqlState, e.causeClass)
      }
    }
  }

  /**
   * Has [read] carried out on a thread of the I/O pool and waits for it within the resource's limit
   * on a statement or [READ_LIMIT_MILLIS], whichever is shorter (ADR-035). When the request ends
   * first, or the limit is reached, the read is cancelled in the database and its connection cut:
   * that is what ends a read that waits on a socket.
   */
  private suspend fun execute(read: ReadOnlyRead): ReadOnlyRows =
      withTimeout(minOf(read.timeoutMillis, READ_LIMIT_MILLIS)) {
        coroutineScope {
          val work =
              async(Dispatchers.IO) {
                try {
                  read.run()
                } catch (e: ReadOnlyException) {
                  // A read that was cancelled ends as a coroutine that was cancelled: whoever
                  // cancelled it (the request, the limit on time) has the say.
                  if (e.failure == ReadOnlyFailure.CANCELLED)
                      throw CancellationException("cancelled")
                  throw e
                }
              }
          try {
            work.await()
          } catch (e: CancellationException) {
            read.cancel()
            throw e
          }
        }
      }

  private suspend fun <T> withIo(block: () -> T): T = withContext(Dispatchers.IO) { block() }

  companion object {
    /** The longest a view read may take, whatever the resource allows; fixed, not configured. */
    const val READ_LIMIT_MILLIS = 30_000L
  }
}

/** The word for the result of a read in the log, the trace and the metrics. */
val ViewReadResult.code: String
  get() =
      when (this) {
        is ViewReadResult.Page -> "ok"
        is ViewReadResult.Ambiguous -> "ambiguous_version"
        ViewReadResult.DefinitionNotFound -> "definition_not_found"
        ViewReadResult.ViewNotFound -> "view_not_found"
        is ViewReadResult.InvalidParameter -> "invalid_parameter"
        is ViewReadResult.ResourceUnavailable -> "resource_unavailable"
        ViewReadResult.Busy -> "busy"
        ViewReadResult.QueryRejected -> "query_rejected"
        ViewReadResult.SchemaMismatch -> "schema_mismatch"
        ViewReadResult.TooLarge -> "too_large"
        ViewReadResult.Timeout -> "timeout"
        ViewReadResult.ConnectionFailed -> "connection_failed"
        is ViewReadResult.Failed -> "failed"
      }
