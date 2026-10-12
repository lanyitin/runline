package dev.lawlan.runline.engine.view

import dev.lawlan.runline.core.ViewParamType
import dev.lawlan.runline.engine.artifact.ViewDoc

/** Why a value of a request is refused. [wire] is the word the API uses. */
enum class ParameterProblem(val wire: String) {
  MISSING("missing"),
  INVALID_TYPE("invalid_type"),
  NOT_AN_OPTION("not_an_option"),
  REPEATED("repeated"),
  UNKNOWN("unknown"),
  OUT_OF_RANGE("out_of_range"),
}

/** The first thing wrong with the parameters of a request: which one, never what it was given. */
class ParameterRefusal(val name: String, val problem: ParameterProblem)

/**
 * The values a request gives for the parameters a view declares (ADR-033): checked against the
 * declaration, and made into what a query is given. A value is only ever a value: text, a number,
 * or nothing for an optional parameter that was not given.
 */
object ViewParameters {
  /** The values by parameter name, in the order the view declares them, or what is wrong. */
  fun bind(view: ViewDoc, given: Map<String, List<String>>): Result {
    val declared = view.parameters.map { it.name }.toSet()
    given.keys
        .firstOrNull { it !in declared }
        ?.let {
          return Result.Refused(ParameterRefusal(it, ParameterProblem.UNKNOWN))
        }
    val values = LinkedHashMap<String, Any?>(view.parameters.size)
    for (parameter in view.parameters) {
      val raw = given[parameter.name].orEmpty()
      fun refuse(problem: ParameterProblem) =
          Result.Refused(ParameterRefusal(parameter.name, problem))
      if (raw.size > 1) return refuse(ParameterProblem.REPEATED)
      // An empty value is a value that was not given.
      val text = raw.singleOrNull()?.takeIf { it.isNotEmpty() }
      if (text == null) {
        if (parameter.required) return refuse(ParameterProblem.MISSING)
        values[parameter.name] = null
        continue
      }
      values[parameter.name] =
          when (parameter.type) {
            ViewParamType.INTEGER.name ->
                text.toLongOrNull() ?: return refuse(ParameterProblem.INVALID_TYPE)
            ViewParamType.CHOICE.name ->
                text.takeIf { it in parameter.options }
                    ?: return refuse(ParameterProblem.NOT_AN_OPTION)
            else -> text
          }
    }
    return Result.Bound(values)
  }

  sealed interface Result {
    class Bound(val values: Map<String, Any?>) : Result

    class Refused(val refusal: ParameterRefusal) : Result
  }

  const val DEFAULT_LIMIT = 50
  const val MAX_LIMIT = 200

  /** The page asked for: [limit] and [offset] as given, or the first thing wrong with them. */
  fun page(limit: String?, offset: String?): PageResult {
    val rows =
        if (limit == null) DEFAULT_LIMIT
        else
            limit.toIntOrNull()
                ?: return PageResult.Refused(
                    ParameterRefusal("limit", ParameterProblem.INVALID_TYPE)
                )
    if (rows !in 1..MAX_LIMIT) {
      return PageResult.Refused(ParameterRefusal("limit", ParameterProblem.OUT_OF_RANGE))
    }
    val skipped =
        if (offset == null) 0L
        else
            offset.toLongOrNull()
                ?: return PageResult.Refused(
                    ParameterRefusal("offset", ParameterProblem.INVALID_TYPE)
                )
    if (skipped < 0)
        return PageResult.Refused(ParameterRefusal("offset", ParameterProblem.OUT_OF_RANGE))
    return PageResult.Page(rows, skipped)
  }

  sealed interface PageResult {
    class Page(val limit: Int, val offset: Long) : PageResult

    class Refused(val refusal: ParameterRefusal) : PageResult
  }
}
