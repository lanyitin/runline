package dev.lawlan.runline.accessors

/** How the rows of a read are ordered: a column of the read, and the way. */
data class ReadOrder(val column: String, val descending: Boolean = false)

/**
 * One page of a read-only query (ADR-033). [sql] is the text a pipeline declared and is run as one
 * read-only statement that refers to [parameters] by name (`:name`, ADR-034), each `null`, text or
 * a Long, and bound as a value wherever the name is used. Only the [columns] are returned, in that
 * order, ordered by [order] and then by the columns themselves, so that pages follow one another
 * without a row twice or missed; [offset] rows are skipped and at most [limit] returned.
 */
class ReadOnlyQuery(
    val sql: String,
    val parameters: Map<String, Any?>,
    val columns: List<String>,
    val order: List<ReadOrder>,
    val limit: Int,
    val offset: Long,
)

/** A page of rows, each with a value for every column asked for; [hasMore] says another follows. */
class ReadOnlyRows(val rows: List<List<Any?>>, val hasMore: Boolean)

/** Why a read did not give rows. Never carries the statement or a value of it. */
enum class ReadOnlyFailure {
  /** The text is not one statement that only reads, or the database refused it as such. */
  QUERY_REJECTED,

  /** What the query names is not in the database (a column or table that is not there). */
  SCHEMA_MISMATCH,

  /** More rows or bytes than the resource's limits allow. */
  RESULT_TOO_LARGE,

  /** The database could not be reached, or did not accept the account. */
  CONNECTION_FAILED,

  /** The resource cannot be used now (its password or certificates cannot be had). */
  UNAVAILABLE,

  /** The read was cancelled from outside. */
  CANCELLED,

  /** Anything else; [ReadOnlyException.sqlState] is all that is known of it. */
  FAILED,
}

/**
 * A read that failed. The message is fixed and the cause is not chained: a message of a database
 * can hold a statement or a value, and neither may reach a response or a log.
 */
class ReadOnlyException(
    val failure: ReadOnlyFailure,
    val sqlState: String? = null,
    /** The class of the exception that was the cause, to find it in the code without its words. */
    val causeClass: String? = null,
) : RuntimeException(failure.name)

/**
 * A read of a resource that only reads (ADR-033): independent of the runs that hold the resource,
 * with a connection of its own that is closed when it ends. [run] blocks; [cancel] may be called
 * from another thread to stop it, and [run] then fails with [ReadOnlyFailure.CANCELLED].
 */
interface ReadOnlyRead {
  /** The longest [run] may take, as the resource says (its limit on a statement). */
  val timeoutMillis: Long

  fun run(): ReadOnlyRows

  fun cancel()
}
