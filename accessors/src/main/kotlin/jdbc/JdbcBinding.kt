package dev.lawlan.runline.accessors.jdbc

import dev.lawlan.runline.accessors.ResourceBinding
import dev.lawlan.runline.accessors.ResourceOperationFailure
import dev.lawlan.runline.core.ResourceFailure
import dev.lawlan.runline.core.ResourceTypes
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** What the resource logs in with: the account's password, or nothing, or what cannot be had. */
sealed interface JdbcCredential {
  /** The account needs no password. */
  data object None : JdbcCredential

  /** The password; it is never shown, and never part of a message. */
  class Password(internal val value: String) : JdbcCredential {
    override fun toString() = "Password(***)"
  }

  /** The resource names a password that the keystore cannot give: nothing can connect. */
  data object Unavailable : JdbcCredential
}

/** What the host learns of the statements of a run: where it counts them and times them. */
interface JdbcObserver {
  /** One statement or transaction operation ended; never the statement or a value. */
  fun finished(resource: String, operation: String, millis: Long, failure: ResourceFailure?) {}

  /** A connection could not be had for a statement. */
  fun acquireFailed(resource: String, failure: ResourceFailure) {}

  companion object {
    val NONE: JdbcObserver = object : JdbcObserver {}
  }
}

/**
 * The statements of one run against one `jdbc-pool` resource (ADR-019, WI-48). The address, the
 * account, the password and the driver are the resource's: a call carries SQL text and parameters
 * and nothing else is read from it. A run is told what kind of failure it was and, for the
 * database's own refusals, the SQLState; what the database or the driver said goes to the host's
 * log under an errorId.
 */
class JdbcBinding
internal constructor(
    private val resource: String,
    private val settings: JdbcSettings,
    private val profile: JdbcProfile,
    private val credential: JdbcCredential,
    private val pool: JdbcConnectionPool,
    private val observer: JdbcObserver,
) : ResourceBinding {
  override val type: String = ResourceTypes.JDBC_POOL

  /** The transaction of the run on this resource, if one is open; one statement at a time. */
  private class Transaction(val connection: Connection) {
    val lock = ReentrantLock()
  }

  @Volatile private var transaction: Transaction? = null
  private val transactionLock = ReentrantLock()

  override fun execute(operation: String, arguments: Map<String, Any?>): Any? =
      when (operation) {
        QUERY ->
            statement(arguments) { connection, sql, parameters ->
              rows(connection, sql, parameters)
            }
        UPDATE ->
            statement(arguments) { connection, sql, parameters ->
              count(connection, sql, parameters)
            }
        BEGIN -> begin()
        COMMIT -> end(commit = true)
        ROLLBACK -> end(commit = false)
        else -> throw ResourceOperationFailure(ResourceFailure.INVALID_ARGUMENT)
      }

  private fun statement(
      arguments: Map<String, Any?>,
      run: (Connection, String, List<Any?>) -> Any?,
  ): Any? {
    val sql = arguments["sql"] as String
    val parameters = (arguments["parameters"] as? List<*>)?.toList() ?: emptyList()
    val open = transaction
    if (open != null)
        return open.lock.withLock { guarded { run(open.connection, sql, parameters) } }
    val connection = connect()
    try {
      return guarded { run(connection, sql, parameters) }
    } finally {
      pool.release(connection)
    }
  }

  private fun connect(): Connection =
      try {
        pool.borrow()
      } catch (e: SQLException) {
        throw failure(e)
      }

  private fun begin(): Any? {
    transactionLock.withLock {
      if (transaction != null) throw ResourceOperationFailure(ResourceFailure.TRANSACTION_STATE)
      val connection = connect()
      try {
        connection.autoCommit = false
      } catch (e: SQLException) {
        pool.release(connection)
        throw failure(e)
      }
      transaction = Transaction(connection)
    }
    return null
  }

  private fun end(commit: Boolean): Any? {
    transactionLock.withLock {
      val open = transaction ?: throw ResourceOperationFailure(ResourceFailure.TRANSACTION_STATE)
      open.lock.withLock {
        transaction = null
        try {
          if (commit) open.connection.commit() else open.connection.rollback()
          open.connection.autoCommit = true
        } catch (e: SQLException) {
          throw failure(e)
        } finally {
          pool.release(open.connection)
        }
      }
    }
    return null
  }

  /** Runs [body], and turns what the database says into what a run is told. */
  private fun <T> guarded(body: () -> T): T =
      try {
        body()
      } catch (e: SQLException) {
        throw failure(e)
      }

  private fun failure(e: SQLException): ResourceOperationFailure {
    val classified = profile.classify(e)
    return ResourceOperationFailure(
        classified.failure,
        e,
        sqlState = classified.sqlState,
        withErrorId = true,
    )
  }

  private fun rows(connection: Connection, sql: String, parameters: List<Any?>): Any? =
      prepare(connection, sql, parameters).use { statement ->
        val result =
            if (statement is PreparedStatement) statement.executeQuery()
            else statement.executeQuery(sql)
        result.use { read(it) }
      }

  private fun count(connection: Connection, sql: String, parameters: List<Any?>): Any? =
      prepare(connection, sql, parameters).use { statement ->
        if (statement is PreparedStatement) statement.executeLargeUpdate()
        else statement.executeLargeUpdate(sql)
      }

  /**
   * A statement for [sql] as it is. Without parameters it is a plain statement, so that no `?` of
   * the text is taken for a parameter; the JDBC escape syntax is never processed.
   */
  private fun prepare(connection: Connection, sql: String, parameters: List<Any?>): Statement {
    if (parameters.isEmpty())
        return connection.createStatement().also { it.setEscapeProcessing(false) }
    val statement = connection.prepareStatement(sql)
    try {
      statement.setEscapeProcessing(false)
      parameters.forEachIndexed { index, value -> profile.values.bind(statement, index + 1, value) }
    } catch (e: Throwable) {
      runCatching { statement.close() }
      throw e
    }
    return statement
  }

  private fun read(result: ResultSet): Map<String, Any?> {
    val metaData = result.metaData
    val columns = java.util.ArrayList<String>()
    for (index in 1..metaData.columnCount) columns += metaData.getColumnLabel(index)
    val rows = java.util.ArrayList<List<Any?>>()
    while (result.next()) {
      val row = java.util.ArrayList<Any?>(columns.size)
      for (index in 1..columns.size) row += profile.values.read(result, index)
      rows += row
    }
    val answer = java.util.HashMap<String, Any?>()
    answer["columns"] = columns
    answer["rows"] = rows
    return answer
  }

  private companion object {
    const val QUERY = "jdbc.query"
    const val UPDATE = "jdbc.update"
    const val BEGIN = "jdbc.begin"
    const val COMMIT = "jdbc.commit"
    const val ROLLBACK = "jdbc.rollback"
  }
}
