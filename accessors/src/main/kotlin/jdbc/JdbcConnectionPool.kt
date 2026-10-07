package dev.lawlan.runline.accessors.jdbc

import java.sql.Connection
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/** The pool has no connection for a call: it is as large as the capacity allows and all are out. */
internal class PoolExhausted : RuntimeException("no connection is left in the pool")

/**
 * The connections of one generation of a `jdbc-pool` resource. Its size is the most the runs that
 * may hold the resource can use at once (capacity times connections per run), so a run that is
 * within its own share always finds a connection: nothing waits here, which is what keeps the
 * hold-the-whole-resource-until-the-end rule of shared resources free of deadlock. A connection is
 * made when it is first needed.
 */
internal class JdbcConnectionPool(
    private val maxConnections: Int,
    private val open: () -> Connection,
) : AutoCloseable {
  private val lock = Any()
  private val idle = ArrayDeque<Connection>()
  private var out = 0
  private val closed = AtomicBoolean()

  val active: Int
    get() = synchronized(lock) { out }

  fun borrow(): Connection {
    synchronized(lock) {
      check(!closed.get()) { "the pool is closed" }
      idle.pollFirst()?.let {
        out++
        return it
      }
      if (out + idle.size >= maxConnections) throw PoolExhausted()
      out++
    }
    try {
      return open()
    } catch (e: Throwable) {
      synchronized(lock) { out-- }
      throw e
    }
  }

  /** Gives [connection] back; it is kept for the next call unless the pool is closed. */
  fun release(connection: Connection) {
    val keep =
        synchronized(lock) {
          out--
          if (closed.get()) false else idle.addFirst(connection).let { true }
        }
    if (!keep) runCatching { connection.close() }
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    val toClose = synchronized(lock) { idle.toList().also { idle.clear() } }
    toClose.forEach { runCatching { it.close() } }
  }
}
