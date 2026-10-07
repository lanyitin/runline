package dev.lawlan.runline.accessors.openai

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * The events of a server-sent event stream, read one at a time from [read], which fills a buffer
 * with what has come and returns how many bytes that is, or -1 at the end. An event is the text of
 * its `data` lines.
 */
internal class ServerSentEvents(private val read: (ByteArray) -> Int) {
  private val buffer = ByteArray(BUFFER)
  private var position = 0
  private var limit = 0

  /** The data of the next event, or null when the stream has ended. */
  fun next(): String? {
    val data = StringBuilder()
    var any = false
    while (true) {
      val line = line() ?: return null
      if (line.isEmpty()) {
        if (any) return data.toString()
        continue
      }
      if (line.startsWith("data:")) {
        if (any) data.append('\n')
        data.append(line.removePrefix("data:").removePrefix(" "))
        any = true
      }
    }
  }

  private fun line(): String? {
    val out = ByteArrayOutputStream()
    while (true) {
      if (position == limit) {
        limit = read(buffer)
        position = 0
        if (limit < 0) {
          limit = 0
          return null
        }
      }
      val b = buffer[position++]
      if (b == '\n'.code.toByte()) return out.toString(StandardCharsets.UTF_8)
      out.write(b.toInt())
    }
  }

  private companion object {
    const val BUFFER = 8 * 1024
  }
}
