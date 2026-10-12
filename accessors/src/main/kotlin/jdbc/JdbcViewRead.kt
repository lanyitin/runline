package dev.lawlan.runline.accessors.jdbc

import dev.lawlan.runline.accessors.ReadOnlyException
import dev.lawlan.runline.accessors.ReadOnlyFailure
import dev.lawlan.runline.accessors.ReadOnlyQuery
import dev.lawlan.runline.accessors.ReadOnlyRead
import dev.lawlan.runline.accessors.ReadOnlyRows
import dev.lawlan.runline.accessors.tls.ResourceTls
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement

/**
 * A read-only read of a `jdbc-pool` resource for a view (ADR-033, WI-1301): a connection of its
 * own, apart from the pool the runs share, which is closed when the read ends. The query is run as
 * the inside of a `SELECT` of its own, so that it can only be one statement that returns rows, and
 * on a connection that the database is told to keep from changing anything.
 */
class JdbcViewRead(
    private val profile: JdbcProfile,
    private val settings: JdbcSettings,
    private val credential: JdbcCredential,
    private val query: ReadOnlyQuery,
    private val tls: ResourceTls? = null,
) : ReadOnlyRead {
  override val timeoutMillis: Long = settings.statementTimeoutMillis

  @Volatile private var cancelled = false
  @Volatile private var connection: Connection? = null
  @Volatile private var running: Statement? = null

  override fun run(): ReadOnlyRows {
    val dialect = profile.reading
    if (credential is JdbcCredential.Unavailable) {
      throw ReadOnlyException(ReadOnlyFailure.UNAVAILABLE)
    }
    val checked = singleStatement(dialect)
    val values =
        checked.parameters.map {
          // A parameter that is not given is `null` in the map; one that is not there is wrong.
          if (it !in query.parameters) rejected()
          query.parameters[it]
        }
    val statement = statementText(dialect, checked.text)
    val poolTls = tls?.let { PoolTls(it, profile) }
    try {
      connection =
          JdbcConnector.open(
              profile,
              settings,
              (credential as? JdbcCredential.Password)?.value,
              tls = poolTls,
              reading = true,
          )
      return page(statement, values)
    } catch (e: SQLException) {
      if (cancelled) throw ReadOnlyException(ReadOnlyFailure.CANCELLED)
      throw ReadOnlyException(dialect.classify(e), e.sqlState, e.javaClass.name)
    } finally {
      runCatching { connection?.close() }
      poolTls?.close()
    }
  }

  private fun page(statement: String, values: List<Any?>): ReadOnlyRows {
    val open = checkNotNull(connection)
    open.prepareStatement(statement).use { prepared ->
      values.forEachIndexed { i, v -> profile.values.bind(prepared, i + 1, v) }
      prepared.setLong(values.size + 1, query.limit + 1L)
      prepared.setLong(values.size + 2, query.offset)
      running = prepared
      // A cancel that came before the statement was known to it is seen here.
      if (cancelled) throw ReadOnlyException(ReadOnlyFailure.CANCELLED)
      prepared.executeQuery().use { result ->
        val rows = ArrayList<List<Any?>>()
        var size = 0L
        while (result.next()) {
          val row = query.columns.indices.map { profile.values.read(result, it + 1) }
          // The row that only tells there are more is not part of the answer.
          if (rows.size < query.limit) {
            size += row.sumOf(::sizeOf)
            if (rows.size >= settings.maxRows || size > settings.maxResponseBytes) {
              throw ReadOnlyException(ReadOnlyFailure.RESULT_TOO_LARGE)
            }
          }
          rows += row
        }
        if (cancelled) throw ReadOnlyException(ReadOnlyFailure.CANCELLED)
        return ReadOnlyRows(rows.take(query.limit), rows.size > query.limit)
      }
    }
  }

  /**
   * Stops the read: the statement is cancelled in the database and the connection is cut, so that
   * it cannot go on even if the database cannot be reached.
   */
  override fun cancel() {
    cancelled = true
    val statement = running
    val open = connection
    val cancelling = Thread.ofPlatform().daemon().start { runCatching { statement?.cancel() } }
    runCatching { cancelling.join(CANCEL_LIMIT_MILLIS) }
    runCatching { open?.abort(Runnable::run) }
  }

  /**
   * The text of the query as one statement, with the names of the parameters it refers to, or the
   * failure that it is not one.
   */
  private fun singleStatement(dialect: ReadDialect): SingleStatement.Accepted =
      SingleStatements.check(query.sql, dialect.syntax) as? SingleStatement.Accepted ?: rejected()

  private fun rejected(): Nothing = throw ReadOnlyException(ReadOnlyFailure.QUERY_REJECTED)

  private fun statementText(dialect: ReadDialect, sql: String): String {
    val columns = query.columns.joinToString(", ") { dialect.quote(it) }
    val order =
        (query.order.map { dialect.quote(it.column) + if (it.descending) " DESC" else "" } +
                query.columns.map { dialect.quote(it) })
            .joinToString(", ")
    return "SELECT $columns FROM (\n$sql\n) AS v ORDER BY $order LIMIT ? OFFSET ?"
  }

  /** What a value counts for against the limit on the size of an answer (as for a run's). */
  private fun sizeOf(value: Any?): Long =
      when (value) {
        is String -> value.length.toLong()
        is ByteArray -> value.size.toLong()
        else -> 8L
      }

  private companion object {
    const val CANCEL_LIMIT_MILLIS = 2_000L
  }
}
