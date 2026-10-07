package dev.lawlan.runline.accessors.jdbc

import dev.lawlan.runline.accessors.ResourceBinding
import dev.lawlan.runline.accessors.ResourceOperationFailure
import dev.lawlan.runline.core.ResourceFailure
import dev.lawlan.runline.core.ResourceTypes
import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

  /** How many connections the run may use at once; the pool is large enough for every run's. */
  private val share = Semaphore(settings.connectionsPerRun, true)

  /** Cuts off a statement that has run too long; made when it is first needed. */
  private val timer: ScheduledThreadPoolExecutor by lazy {
    ScheduledThreadPoolExecutor(1) { task ->
          Thread.ofPlatform().name("jdbc-statement-timer-$resource").daemon().unstarted(task)
        }
        .apply { removeOnCancelPolicy = true }
  }

  override fun execute(operation: String, arguments: Map<String, Any?>): Any? {
    if (operation !in OPERATIONS) throw refused()
    val started = System.nanoTime()
    var failure: ResourceFailure? = null
    try {
      return when (operation) {
        QUERY ->
            statement(arguments) { connection, sql, parameters ->
              rows(connection, sql, parameters)
            }
        UPDATE ->
            statement(arguments) { connection, sql, parameters ->
              count(connection, sql, parameters)
            }
        BEGIN -> noArguments(arguments).let { begin() }
        COMMIT -> noArguments(arguments).let { end(commit = true) }
        else -> noArguments(arguments).let { end(commit = false) }
      }
    } catch (e: ResourceOperationFailure) {
      failure = e.failure
      throw e
    } catch (e: Throwable) {
      failure = ResourceFailure.FAILED
      throw e
    } finally {
      val millis = (System.nanoTime() - started) / 1_000_000
      runCatching { observer.finished(resource, operation, millis, failure) }
    }
  }

  override fun close() {
    if (timer.isShutdown.not()) timer.shutdownNow()
  }

  private fun refused() = ResourceOperationFailure(ResourceFailure.INVALID_ARGUMENT)

  private fun noArguments(arguments: Map<String, Any?>) {
    if (arguments.isNotEmpty()) throw refused()
  }

  /**
   * Runs one statement. What a call may carry is the text and the parameters, and nothing else is
   * read from it: a member that is anything but those is refused, so that nothing a run puts in a
   * call is taken for where to connect, whom as, or through what.
   */
  private fun statement(
      arguments: Map<String, Any?>,
      run: (Connection, String, List<Any?>) -> Any?,
  ): Any? {
    if (!STATEMENT_MEMBERS.containsAll(arguments.keys)) throw refused()
    val sql = arguments["sql"] as? String ?: throw refused()
    val parameters = parametersOf(arguments["parameters"])
    val open = transaction
    if (open != null) {
      return open.lock.withLock { guarded { run(open.connection, sql, parameters) } }
    }
    val connection = connect()
    try {
      return guarded { run(connection, sql, parameters) }
    } finally {
      give(connection)
    }
  }

  /** The parameters of a call, each one of the kinds a run can give; anything else is refused. */
  private fun parametersOf(given: Any?): List<Any?> {
    if (given == null) return emptyList()
    val list = given as? List<*> ?: throw refused()
    val parameters = ArrayList<Any?>(list.size)
    for (value in list) {
      when (value) {
        null,
        is String,
        is Boolean,
        is Long,
        is Double,
        is BigDecimal,
        is ByteArray -> parameters += value
        else -> throw refused()
      }
    }
    return parameters
  }

  /** A connection of the run's share: the run waits for its own share, never for another run's. */
  private fun connect(): Connection {
    if (credential is JdbcCredential.Unavailable) {
      observer.acquireFailed(resource, ResourceFailure.SECRET_UNAVAILABLE)
      throw ResourceOperationFailure(ResourceFailure.SECRET_UNAVAILABLE)
    }
    if (!share.tryAcquire(settings.quotaWaitMillis, TimeUnit.MILLISECONDS)) {
      observer.acquireFailed(resource, ResourceFailure.QUOTA_WAIT_TIMEOUT)
      throw ResourceOperationFailure(ResourceFailure.QUOTA_WAIT_TIMEOUT)
    }
    try {
      return pool.borrow()
    } catch (e: Throwable) {
      share.release()
      val failure =
          when (e) {
            is SQLException -> failure(e)
            else ->
                ResourceOperationFailure(ResourceFailure.CONNECTION_FAILED, e, withErrorId = true)
          }
      observer.acquireFailed(resource, failure.failure)
      throw failure
    }
  }

  /** Gives a connection, and the run's share of them, back. */
  private fun give(connection: Connection) {
    try {
      pool.release(connection)
    } finally {
      share.release()
    }
  }

  private fun begin(): Any? {
    transactionLock.withLock {
      if (transaction != null) throw ResourceOperationFailure(ResourceFailure.TRANSACTION_STATE)
      val connection = connect()
      try {
        connection.autoCommit = false
      } catch (e: SQLException) {
        give(connection)
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
          give(open.connection)
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

  private fun failure(e: SQLException, timedOut: Boolean = false): ResourceOperationFailure {
    val classified = profile.classify(e)
    val failure = if (timedOut) ResourceFailure.TOTAL_TIMEOUT else classified.failure
    return ResourceOperationFailure(
        failure,
        JdbcFailureCause.of(failure, e, (credential as? JdbcCredential.Password)?.value),
        sqlState = if (timedOut) null else classified.sqlState,
        withErrorId = true,
    )
  }

  private fun rows(connection: Connection, sql: String, parameters: List<Any?>): Any? =
      prepare(connection, sql, parameters).use { statement ->
        statement.maxRows = settings.maxRows + 1
        limited(statement) {
          val result =
              if (statement is PreparedStatement) statement.executeQuery()
              else statement.executeQuery(sql)
          result.use { read(it) }
        }
      }

  private fun count(connection: Connection, sql: String, parameters: List<Any?>): Any? =
      prepare(connection, sql, parameters).use { statement ->
        limited(statement) {
          if (statement is PreparedStatement) statement.executeLargeUpdate()
          else statement.executeLargeUpdate(sql)
        }
      }

  /** Runs [body], which executes [statement], and cuts it off when it takes longer than allowed. */
  private fun <T> limited(statement: Statement, body: () -> T): T {
    val timedOut = AtomicBoolean()
    val cutOff =
        timer.schedule(
            {
              timedOut.set(true)
              runCatching { statement.cancel() }
            },
            settings.statementTimeoutMillis,
            TimeUnit.MILLISECONDS,
        )
    try {
      return body()
    } catch (e: SQLException) {
      if (timedOut.get()) throw failure(e, timedOut = true)
      throw e
    } finally {
      cutOff.cancel(false)
    }
  }

  /**
   * A statement for [sql] as it is. Without parameters it is a plain statement, so that no `?` of
   * the text is taken for a parameter; the JDBC escape syntax is never processed.
   */
  private fun prepare(connection: Connection, sql: String, parameters: List<Any?>): Statement {
    if (parameters.isEmpty()) {
      return connection.createStatement().also { it.setEscapeProcessing(false) }
    }
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
    var size = 0L
    while (result.next()) {
      if (rows.size >= settings.maxRows) {
        throw ResourceOperationFailure(ResourceFailure.RESPONSE_TOO_LARGE)
      }
      val row = java.util.ArrayList<Any?>(columns.size)
      for (index in 1..columns.size) {
        val value = profile.values.read(result, index)
        size += sizeOf(value)
        row += value
      }
      if (size > settings.maxResponseBytes) {
        throw ResourceOperationFailure(ResourceFailure.RESPONSE_TOO_LARGE)
      }
      rows += row
    }
    val answer = java.util.HashMap<String, Any?>()
    answer["columns"] = columns
    answer["rows"] = rows
    return answer
  }

  /** What a value counts for against the limit on the size of an answer. */
  private fun sizeOf(value: Any?): Long =
      when (value) {
        is String -> value.length.toLong()
        is ByteArray -> value.size.toLong()
        else -> FIXED_SIZE
      }

  private companion object {
    const val QUERY = "jdbc.query"
    const val UPDATE = "jdbc.update"
    const val BEGIN = "jdbc.begin"
    const val COMMIT = "jdbc.commit"
    const val ROLLBACK = "jdbc.rollback"
    val OPERATIONS = setOf(QUERY, UPDATE, BEGIN, COMMIT, ROLLBACK)
    val STATEMENT_MEMBERS = setOf("sql", "parameters")
    const val FIXED_SIZE = 8L
  }
}

/**
 * What the host's log is given for a failure of the database. A message of the database or the
 * driver can hold the address, the account, a statement or a value, so for a refusal of a statement
 * only the category, the SQLState and the kind of exception are kept; for a failure that holds no
 * statement (connecting, logging in, a timeout) the driver's words are kept too, with the password
 * taken out, because they are what a failure to connect is diagnosed from. The exception itself is
 * not chained: its message is in every printout of the chain.
 */
internal class JdbcFailureCause private constructor(message: String) : RuntimeException(message) {
  companion object {
    private const val LONGEST_MESSAGE = 500

    fun of(failure: ResourceFailure, e: SQLException, password: String?): Throwable {
      val said =
          if (failure == ResourceFailure.SQL_ERROR) ""
          else "; the driver said: " + masked(e.message.orEmpty(), password).take(LONGEST_MESSAGE)
      return JdbcFailureCause("$failure sqlState=${e.sqlState} exception=${e.javaClass.name}$said")
    }

    private fun masked(text: String, password: String?): String =
        if (password.isNullOrEmpty()) text else text.replace(password, "***")
  }
}
