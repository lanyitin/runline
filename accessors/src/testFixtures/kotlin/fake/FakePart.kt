package dev.lawlan.runline.accessors.fake

import java.nio.charset.StandardCharsets

/**
 * One part of a `multipart/form-data` body as a server reads it: the `name` and the `filename`
 * (null for a text field) of its `Content-Disposition`, the headers it carried as sent, and its
 * bytes.
 */
class FakePart(
    val name: String,
    val filename: String?,
    /** The part's own headers, names in lower case, in the order they came. */
    val headers: Map<String, List<String>>,
    val bytes: ByteArray,
) {
  val contentType: String?
    get() = headers["content-type"]?.firstOrNull()

  companion object {
    private val CRLF = "\r\n".toByteArray(StandardCharsets.ISO_8859_1)
    private val HEADER_END = "\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1)

    /**
     * The parts of [body] delimited by [boundary] (RFC 7578 and 2046): a delimiter line, the part,
     * a CRLF and the next delimiter, up to the closing one. Null if the body is not so formed, in
     * particular when a part has no `name`.
     */
    fun parse(body: ByteArray, boundary: String): List<FakePart>? {
      val delimiter = "--$boundary".toByteArray(StandardCharsets.ISO_8859_1)
      if (!body.startsWithAt(0, delimiter)) return null
      val parts = mutableListOf<FakePart>()
      var position = delimiter.size
      while (true) {
        if (body.startsWithAt(position, "--".toByteArray())) return parts
        if (!body.startsWithAt(position, CRLF)) return null
        position += CRLF.size
        val headerEnd = body.indexOf(HEADER_END, position)
        if (headerEnd < 0) return null
        val headers = headersOf(body.copyOfRange(position, headerEnd)) ?: return null
        val contentStart = headerEnd + HEADER_END.size
        val next = body.indexOf(CRLF + delimiter, contentStart)
        if (next < 0) return null
        val disposition = headers["content-disposition"]?.firstOrNull() ?: return null
        val name = attribute(disposition, "name") ?: return null
        parts +=
            FakePart(
                name,
                attribute(disposition, "filename"),
                headers,
                body.copyOfRange(contentStart, next),
            )
        position = next + CRLF.size + delimiter.size
      }
    }

    private fun headersOf(block: ByteArray): Map<String, List<String>>? {
      val headers = LinkedHashMap<String, MutableList<String>>()
      for (line in String(block, StandardCharsets.UTF_8).split("\r\n")) {
        val colon = line.indexOf(':')
        if (colon <= 0) return null
        headers.getOrPut(line.substring(0, colon).trim().lowercase()) { mutableListOf() } +=
            line.substring(colon + 1).trim()
      }
      return headers
    }

    /** The value of `attribute="..."` in a header value, without the quotes; null if absent. */
    private fun attribute(header: String, attribute: String): String? =
        Regex("(?:^|;)\\s*$attribute=\"([^\"]*)\"").find(header)?.groupValues?.get(1)

    private fun ByteArray.startsWithAt(at: Int, prefix: ByteArray): Boolean {
      if (at + prefix.size > size) return false
      for (i in prefix.indices) if (this[at + i] != prefix[i]) return false
      return true
    }

    private fun ByteArray.indexOf(needle: ByteArray, from: Int): Int {
      var i = from
      while (i + needle.size <= size) {
        if (startsWithAt(i, needle)) return i
        i++
      }
      return -1
    }
  }
}
