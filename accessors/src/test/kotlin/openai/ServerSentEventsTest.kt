package dev.lawlan.runline.accessors.openai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** How the text of a server-sent event stream becomes events, however it is cut into reads. */
class ServerSentEventsTest {
  /** Events read from [pieces], which arrive as the reads of a connection would. */
  private fun events(vararg pieces: String): List<String> {
    val queue = ArrayDeque(pieces.map { it.encodeToByteArray() })
    val reader = ServerSentEvents { buffer ->
      val piece = queue.removeFirstOrNull() ?: return@ServerSentEvents -1
      piece.copyInto(buffer)
      piece.size
    }
    return generateSequence { reader.next() }.toList()
  }

  @Test
  fun `lines may end in CRLF, in LF`() {
    assertEquals(listOf("a", "b"), events("data: a\r\n\r\ndata: b\n\n"))
  }

  @Test
  fun `comments, other fields and blank lines are not events, and a data line needs no space`() {
    assertEquals(
        listOf("a", "b"),
        events(": keep-alive\n\nevent: delta\nid: 7\nretry: 5\ndata:a\n\n\n\ndata: b\n\n"),
    )
  }

  @Test
  fun `the data lines of one event are joined with a newline`() {
    assertEquals(listOf("one\ntwo"), events("data: one\ndata: two\n\n"))
  }

  @Test
  fun `an event can be cut anywhere between reads, even inside a multibyte character`() {
    val text = "data: 你好\n\n".encodeToByteArray()
    val queue = ArrayDeque(text.map { byteArrayOf(it) })
    val reader = ServerSentEvents { buffer ->
      val piece = queue.removeFirstOrNull() ?: return@ServerSentEvents -1
      buffer[0] = piece[0]
      1
    }

    assertEquals("你好", reader.next())
    assertNull(reader.next())
  }

  @Test
  fun `an event the stream ends in the middle of is not delivered`() {
    assertEquals(listOf("a"), events("data: a\n\ndata: b\n"))
  }
}
