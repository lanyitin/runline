package dev.lawlan.runline.accessors.openai

/**
 * Looks for the API key's bytes in an answer that comes in pieces, wherever the pieces are cut: it
 * keeps the last `key.size - 1` bytes of what it has seen, so that a key that begins in one piece
 * and ends in the next is found as well.
 */
internal class KeyScan(private val key: ByteArray) {
  private var tail = ByteArray(0)

  /** Whether the key is in [piece] ([length] bytes of it) or across the cut from the one before. */
  fun finds(piece: ByteArray, length: Int = piece.size): Boolean {
    if (key.isEmpty()) return false
    val window = tail + piece.copyOf(length)
    tail = window.copyOfRange(maxOf(0, window.size - (key.size - 1)), window.size)
    return indexOf(window) >= 0
  }

  private fun indexOf(window: ByteArray): Int {
    var i = 0
    while (i + key.size <= window.size) {
      var j = 0
      while (j < key.size && window[i + j] == key[j]) j++
      if (j == key.size) return i
      i++
    }
    return -1
  }

  /** How many bytes at the end of a piece may begin a key that goes on in the next one. */
  val holdBack: Int
    get() = maxOf(0, key.size - 1)
}
