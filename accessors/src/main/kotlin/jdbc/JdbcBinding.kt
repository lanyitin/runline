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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
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
    /** Tells the pools that this run is done with its generation; called once. */
    private val done: () -> Unit,
) : ResourceBinding {
  override val type: String = ResourceTypes.JDBC_POOL

  /** A connection of the run, and when it was last used. */
  private class Held(val connection: Connection, var lastUsedNanos: Long = System.nanoTime())

  private val lock = ReentrantLock()
  private val freed = lock.newCondition()

  /** The run's connections that no statement is using, and how many the run has in all. */
  private val idle = java.util.ArrayDeque<Held>()
  private var owned = 0

  /** The transaction of the run on this resource, if one is open; one statement at a time. */
  private class Transaction(val held: Held) {
    val lock = ReentrantLock()
  }

  @Volatile private var transaction: Transaction? = null
  private val transactionLock = ReentrantLock()

  /** The statements running now, with their connections, which an abort cuts off. */
  private val running = ConcurrentHashMap<Statement, Connection>()

  @Volatile private var aborted = false

  /** Cuts off a statement that has run too long; made when it is first needed. */
  private val timerOnce = lazy {
    ScheduledThreadPoolExecutor(1) { task ->
          Thread.ofPlatform().name("jdbc-statement-timer-$resource").daemon().unstarted(task)
        }
        .apply { removeOnCancelPolicy = true }
  }
  private val timer: ScheduledThreadPoolExecutor by timerOnce

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

  /**
   * Stops what the run is doing: every statement that is running is cancelled in the database and
   * its connection is cut, so that it cannot go on even if the database cannot be reached, and
   * whoever waits for a connection is let go. It does not wait for them.
   */
  override fun abort() {
    aborted = true
    lock.withLock { freed.signalAll() }
    for ((statement, connection) in running) {
      // The cancel goes to the database over a new connection and can take as long as that does,
      // so it has a limit; the connection is cut whatever came of it.
      val cancel = Thread.ofPlatform().daemon().start { runCatching { statement.cancel() } }
      runCatching { cancel.join(CANCEL_LIMIT_MILLIS) }
      runCatching { connection.abort(Runnable::run) }
    }
  }

  /**
   * Ends the run's use of the resource: an open transaction is rolled back, the connections are
   * given back to the pool, which cleans each of them before another run can have it (and closes
   * one that it cannot show to be clean), and the run leaves its generation.
   */
  override fun close() {
    try {
      val open = transaction
      transaction = null
      lock.withLock {
        if (open != null) idle += open.held
        while (idle.isNotEmpty()) {
          val held = idle.removeFirst()
          // The pool cleans it, or closes it when it cannot show it clean (a connection cut by
          // an abort is closed already).
          runCatching { pool.release(held.connection) }
        }
        owned = 0
      }
    } finally {
      if (timerOnce.isInitialized()) timer.shutdownNow()
      done()
    }
  }

  private fun refused() = ResourceOperationFailure(ResourceFailure.INVALID_ARGUMENT)

  private fun cancelled() = ResourceOperationFailure(ResourceFailure.CANCELLED)

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
      return open.lock.withLock { guarded { run(open.held.connection, sql, parameters) } }
    }
    val held = acquire()
    var keep = true
    try {
      return guarded { run(held.connection, sql, parameters) }
    } catch (e: ResourceOperationFailure) {
      // A connection that was cut off, or that broke, or whose statement was stopped halfway, is
      // not used again.
      if (e.failure in UNUSABLE_AFTER || held.connection.isClosed) keep = false
      throw e
    } finally {
      if (keep) free(held) else drop(held)
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

  /**
   * A connection for a statement: one of the run's that is free, or a new one of the pool's while
   * the run has fewer than its share, or else the run waits for one of its own. The pool has room
   * for every run's whole share, so what is waited for is only the run's own.
   */
  private fun acquire(): Held {
    if (credential is JdbcCredential.Unavailable) {
      observer.acquireFailed(resource, ResourceFailure.SECRET_UNAVAILABLE)
      throw ResourceOperationFailure(ResourceFailure.SECRET_UNAVAILABLE)
    }
    val deadline = System.nanoTime() + settings.quotaWaitMillis * 1_000_000
    lock.lock()
    try {
      while (true) {
        if (aborted) throw cancelled()
        val free = idle.pollFirst()
        if (free != null) {
          if (stillGood(free)) return free
          owned--
          pool.discard(free.connection)
          continue
        }
        if (owned < settings.connectionsPerRun) {
          owned++
          break
        }
        val left = deadline - System.nanoTime()
        if (left <= 0) {
          observer.acquireFailed(resource, ResourceFailure.QUOTA_WAIT_TIMEOUT)
          throw ResourceOperationFailure(ResourceFailure.QUOTA_WAIT_TIMEOUT)
        }
        freed.awaitNanos(left)
      }
    } finally {
      lock.unlock()
    }
    try {
      return Held(pool.borrow())
    } catch (e: Throwable) {
      lock.withLock {
        owned--
        freed.signalAll()
      }
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

  /**
   * A connection that has been idle a while may have been dropped by the database or the network.
   */
  private fun stillGood(held: Held): Boolean {
    if (held.connection.isClosed) return false
    if (System.nanoTime() - held.lastUsedNanos < STALE_NANOS) return true
    return runCatching { held.connection.isValid(2) }.getOrDefault(false)
  }

  /** The statement is done with [held], which the run keeps for its next one. */
  private fun free(held: Held) {
    held.lastUsedNanos = System.nanoTime()
    lock.withLock {
      idle += held
      freed.signalAll()
    }
  }

  /** [held] is closed and no longer the run's. */
  private fun drop(held: Held) {
    pool.discard(held.connection)
    lock.withLock {
      owned--
      freed.signalAll()
    }
  }

  private fun begin(): Any? {
    transactionLock.withLock {
      if (transaction != null) throw ResourceOperationFailure(ResourceFailure.TRANSACTION_STATE)
      val held = acquire()
      try {
        held.connection.autoCommit = false
      } catch (e: SQLException) {
        drop(held)
        throw failure(e)
      }
      transaction = Transaction(held)
    }
    return null
  }

  private fun end(commit: Boolean): Any? {
    transactionLock.withLock {
      val open = transaction ?: throw ResourceOperationFailure(ResourceFailure.TRANSACTION_STATE)
      open.lock.withLock {
        transaction = null
        val connection = open.held.connection
        try {
          if (commit) connection.commit() else connection.rollback()
          connection.autoCommit = true
          free(open.held)
        } catch (e: SQLException) {
          // Whatever state it is in, the connection is closed, which ends the transaction.
          drop(open.held)
          throw failure(e)
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
    val failure =
        when {
          aborted -> ResourceFailure.CANCELLED
          timedOut -> ResourceFailure.TOTAL_TIMEOUT
          else -> classified.failure
        }
    return ResourceOperationFailure(
        failure,
        JdbcFailureCause.of(failure, e, (credential as? JdbcCredential.Password)?.value),
        sqlState = if (timedOut || aborted) null else classified.sqlState,
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
    running[statement] = statement.connection
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
      val result = body()
      // An answer that came as the run was cut off is not given: nothing may complete after it.
      if (aborted) throw cancelled()
      return result
    } catch (e: SQLException) {
      if (timedOut.get()) throw failure(e, timedOut = true)
      throw e
    } finally {
      cutOff.cancel(false)
      running.remove(statement)
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
    const val CANCEL_LIMIT_MILLIS = 2_000L
    const val STALE_NANOS = 1_000_000_000L
    val UNUSABLE_AFTER =
        setOf(
            ResourceFailure.CANCELLED,
            ResourceFailure.TOTAL_TIMEOUT,
            ResourceFailure.CONNECTION_FAILED,
            ResourceFailure.CONNECT_TIMEOUT,
        )
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
