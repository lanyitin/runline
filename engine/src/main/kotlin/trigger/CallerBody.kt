package dev.lawlan.runline.engine.trigger

import dev.lawlan.runline.engine.artifact.ParameterDoc
import dev.lawlan.runline.engine.run.namesWithNul
import java.io.InputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What reading the body of a webhook call gave (ADR-036). */
sealed interface CallerBodyResult {
  /** Every parameter the trigger lets the caller provide that the caller gave, as text. */
  data class Values(val values: Map<String, String>) : CallerBodyResult

  data class BadBody(val problem: BodyProblem) : CallerBodyResult

  data class BadParameters(val problems: List<CallerParameterProblem>) : CallerBodyResult
}

/**
 * Reads the body of a webhook call as the parameters the caller provides. The body is untrusted: no
 * more than [maxBytes] of it are taken, it must be a JSON object, and only the names the trigger
 * lists are accepted, each with a text value. Nothing about what the body holds is kept in a
 * problem, only which parameter is wrong and how.
 */
class CallerBody(private val maxBytes: Long) {
  /**
   * Reads [body]. [provided] are the parameters the caller may give; those among them that
   * [declared] says are required must be there.
   */
  fun read(
      body: () -> InputStream,
      provided: List<String>,
      declared: List<ParameterDoc>,
  ): CallerBodyResult {
    val bytes = body().use { it.readNBytes(maxBytes.plus(1).coerceAtMost(MAX_ARRAY).toInt()) }
    if (bytes.size > maxBytes) return CallerBodyResult.BadBody(BodyProblem.TOO_LARGE)
    val text = bytes.toString(Charsets.UTF_8)
    // The parser recurses with the nesting of its input and a StackOverflowError is no exception,
    // so a body nested deeper than anything wanted here is not parsed at all.
    if (nestsDeeperThan(text, MAX_NESTING)) {
      return CallerBodyResult.BadBody(BodyProblem.NOT_A_JSON_OBJECT)
    }
    val entries =
        try {
          (Json.parseToJsonElement(text) as? JsonObject)
              ?: return CallerBodyResult.BadBody(BodyProblem.NOT_A_JSON_OBJECT)
        } catch (e: SerializationException) {
          return CallerBodyResult.BadBody(BodyProblem.NOT_A_JSON_OBJECT)
        } catch (e: IllegalArgumentException) {
          return CallerBodyResult.BadBody(BodyProblem.NOT_A_JSON_OBJECT)
        }

    val values = mutableMapOf<String, String>()
    val problems = mutableListOf<CallerParameterProblem>()
    for ((key, value) in entries) {
      when {
        key !in provided ->
            problems +=
                CallerParameterProblem(key.take(MAX_NAME_ECHO), CallerProblemKind.UNDECLARED)
        value is JsonPrimitive && value.isString -> values[key] = value.content
        else -> problems += CallerParameterProblem(key, CallerProblemKind.NOT_TEXT)
      }
    }
    namesWithNul(values).forEach {
      values -= it
      problems += CallerParameterProblem(it, CallerProblemKind.NUL_IN_VALUE)
    }
    declared
        .filter { it.required && it.name in provided && it.name !in entries }
        .forEach { problems += CallerParameterProblem(it.name, CallerProblemKind.MISSING) }
    return if (problems.isEmpty()) CallerBodyResult.Values(values)
    else CallerBodyResult.BadParameters(problems.sortedBy { it.name })
  }

  /** Whether brackets and braces outside strings are ever open more than [limit] deep. */
  private fun nestsDeeperThan(text: String, limit: Int): Boolean {
    var depth = 0
    var inString = false
    var escaped = false
    for (c in text) {
      when {
        escaped -> escaped = false
        inString -> if (c == '\\') escaped = true else if (c == '"') inString = false
        c == '"' -> inString = true
        c == '{' || c == '[' -> if (++depth > limit) return true
        c == '}' || c == ']' -> depth--
      }
    }
    return false
  }

  private companion object {
    /** An object of text values is one deep; a little more leaves room without the risk. */
    const val MAX_NESTING = 8

    const val MAX_ARRAY = Int.MAX_VALUE - 8L

    /** A name outside the list is whatever the caller sent; only its start is repeated back. */
    const val MAX_NAME_ECHO = 100
  }
}
