package dev.lawlan.runline.engine.support

import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A real server on a real port that takes every connection and the request on it, and then either
 * says nothing at all or sends a part of an answer and stops. It stands for an Engine or a database
 * that does not respond, which is what once held a verification for ten minutes.
 */
class StuckServer(private val reply: String) : Closeable {
  private val server = ServerSocket(0)
  private val connections = CopyOnWriteArrayList<Socket>()
  val port: Int = server.localPort
  val base = "http://localhost:$port"

  init {
    thread(isDaemon = true, name = "stuck-server") {
      try {
        while (true) {
          val socket = server.accept()
          connections += socket
          thread(isDaemon = true) {
            runCatching {
              // The request has arrived once its header ends; then answer as told, or not at all.
              val input = socket.getInputStream()
              var tail = ""
              while (!tail.endsWith("\r\n\r\n")) tail = (tail + input.read().toChar()).takeLast(4)
              socket.getOutputStream().apply {
                write(reply.toByteArray())
                flush()
              }
            }
          }
        }
      } catch (_: java.io.IOException) {}
    }
  }

  override fun close() {
    server.close()
    connections.forEach { runCatching { it.close() } }
  }
}
