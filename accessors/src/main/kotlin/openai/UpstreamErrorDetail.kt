package dev.lawlan.runline.accessors.openai

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The only two things a pipeline learns from the body of a service's 4xx (ADR-043): `error.type`
 * and `error.message` of the OpenAI error format. Each is masked, reduced to printable text and cut
 * to a limit; anything else in the body, and a body that is not that format, gives nothing.
 */
internal class UpstreamErrorDetail(val type: String, val message: String) {
  companion object {
    /** How much of the front of a body is read. */
    const val BODY_PREFIX_BYTES = 4096

    const val MAX_TYPE = 64
    const val MAX_MESSAGE = 300
    private const val CUT = '…'

    val NONE = UpstreamErrorDetail("", "")

    fun of(prefix: ByteArray, mask: (String) -> String): UpstreamErrorDetail {
      val error =
          try {
            Json.parseToJsonElement(prefix.toString(Charsets.UTF_8)).jsonObject["error"]
                as? JsonObject
          } catch (_: SerializationException) {
            null
          } catch (_: IllegalArgumentException) {
            null
          } ?: return NONE
      return UpstreamErrorDetail(
          text(error["type"], MAX_TYPE, mask),
          text(error["message"], MAX_MESSAGE, mask),
      )
    }

    private fun text(element: Any?, limit: Int, mask: (String) -> String): String {
      val primitive = element as? JsonPrimitive ?: return ""
      if (!primitive.isString) return ""
      // Masked first, on the whole text, so that a secret the cut would split is still found.
      val printable = printable(mask(primitive.content))
      if (printable.length <= limit) return printable
      var end = limit - 1
      // Never between the two halves of a character outside the basic plane.
      if (Character.isHighSurrogate(printable[end - 1])) end--
      return printable.substring(0, end).trimEnd() + CUT
    }

    /** Control characters and line breaks become one space; nothing else non-printable stays. */
    private fun printable(text: String): String {
      val out = StringBuilder()
      text.codePoints().forEach { c ->
        if (Character.getType(c) !in HIDDEN) out.appendCodePoint(c)
        else if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
      }
      return out.toString().replace(Regex(" {2,}"), " ").trim()
    }

    private val HIDDEN =
        setOf(
                Character.CONTROL,
                Character.FORMAT,
                Character.SURROGATE,
                Character.PRIVATE_USE,
                Character.UNASSIGNED,
                Character.LINE_SEPARATOR,
                Character.PARAGRAPH_SEPARATOR,
            )
            .map { it.toInt() }
            .toSet()
  }
}
