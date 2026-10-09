package dev.lawlan.runline.accessors.fake

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * A real TCP forward on a real port to [targetHost]:[targetPort] that can be frozen: while it is,
 * every connection through it stays open but nothing goes either way, which is what a network that
 * stops answering looks like to both ends (no reset, no close, just silence). What was sent during
 * the freeze is delivered once it is [thaw]ed. Connections made while it is frozen are accepted and
 * reach the target, and are just as silent.
 */
class FreezableForward(private val targetHost: String, private val targetPort: Int) : Closeable {
  private val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
  private val sockets = CopyOnWriteArrayList<Socket>()
  private val lock = ReentrantLock()
  private val thawed = lock.newCondition()
  @Volatile private var frozen = false

  val host = "127.0.0.1"
  val port: Int = server.localPort

  init {
    thread(isDaemon = true, name = "freezable-forward-$port") {
      try {
        while (true) {
          val client = server.accept()
          val upstream = Socket(targetHost, targetPort)
          sockets += client
          sockets += upstream
          pump(client.getInputStream(), upstream.getOutputStream(), client, upstream)
          pump(upstream.getInputStream(), client.getOutputStream(), client, upstream)
        }
      } catch (_: java.io.IOException) {}
    }
  }

  /** From now on nothing goes through, either way, until [thaw]. */
  fun freeze() {
    frozen = true
  }

  /** What was held is delivered and traffic flows again. */
  fun thaw() {
    lock.withLock {
      frozen = false
      thawed.signalAll()
    }
  }

  private fun pump(from: InputStream, to: OutputStream, vararg ends: Socket) {
    thread(isDaemon = true, name = "freezable-forward-pump") {
      val buffer = ByteArray(16 * 1024)
      try {
        while (true) {
          val read = from.read(buffer)
          if (read < 0) break
          lock.withLock { while (frozen) thawed.await() }
          to.write(buffer, 0, read)
          to.flush()
        }
      } catch (_: Exception) {} finally {
        ends.forEach { runCatching { it.close() } }
      }
    }
  }

  override fun close() {
    thaw()
    runCatching { server.close() }
    sockets.forEach { runCatching { it.close() } }
  }
}
