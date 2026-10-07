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
    /**
     * Brings a connection that has been used back to the state of a new one and says whether it is
     * shown to be so; one that is not is closed and never given out again.
     */
    private val clean: (Connection) -> Boolean,
    private val open: () -> Connection,
) : AutoCloseable {
  private val lock = Any()
  private val idle = ArrayDeque<Connection>()

  /** Every connection that is open, in use or not, so that closing the pool closes them all. */
  private val everyOpen = HashSet<Connection>()
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
      val connection = open()
      synchronized(lock) { everyOpen += connection }
      return connection
    } catch (e: Throwable) {
      synchronized(lock) { out-- }
      throw e
    }
  }

  /**
   * Gives [connection] back. It is cleaned first, and kept for the next run only if that worked and
   * showed it clean; otherwise (and when the pool is closed) it is closed.
   */
  fun release(connection: Connection) {
    val usable = !closed.get() && runCatching { clean(connection) }.getOrDefault(false)
    val keep =
        synchronized(lock) {
          out--
          if (usable && !closed.get()) idle.addFirst(connection).let { true } else false
        }
    if (!keep) forget(connection)
  }

  private fun forget(connection: Connection) {
    synchronized(lock) { everyOpen -= connection }
    runCatching { connection.close() }
  }

  /** Closes [connection] instead of keeping it: its state cannot be trusted. */
  fun discard(connection: Connection) {
    synchronized(lock) { out-- }
    forget(connection)
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    val toClose =
        synchronized(lock) {
          idle.clear()
          everyOpen.toList().also { everyOpen.clear() }
        }
    toClose.forEach { runCatching { it.close() } }
  }
}
