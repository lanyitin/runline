package dev.lawlan.runline.engine.run

import dev.lawlan.runline.engine.artifact.ParameterDoc

enum class ParameterProblemKind {
  MISSING,
  UNDECLARED,
}

/** One reason a run's parameters are refused, naming the parameter at fault. */
data class ParameterProblem(val name: String, val kind: ParameterProblemKind)

sealed interface ParameterCheck {
  /** [effective] is what the run receives: supplied values plus defaults of omitted optionals. */
  data class Valid(val effective: Map<String, String>) : ParameterCheck

  data class Invalid(val problems: List<ParameterProblem>) : ParameterCheck
}

/** Checks [supplied] parameters against the declaration in a pipeline's metadata. */
fun validateParameters(
    declared: List<ParameterDoc>,
    supplied: Map<String, String>,
): ParameterCheck {
  val names = declared.map { it.name }.toSet()
  val problems =
      declared
          .filter { it.required && it.name !in supplied }
          .map { ParameterProblem(it.name, ParameterProblemKind.MISSING) } +
          supplied.keys
              .filter { it !in names }
              .sorted()
              .map { ParameterProblem(it, ParameterProblemKind.UNDECLARED) }
  if (problems.isNotEmpty()) return ParameterCheck.Invalid(problems)

  val defaults = declared.filter { !it.required && it.default != null && it.name !in supplied }
  return ParameterCheck.Valid(supplied + defaults.associate { it.name to it.default!! })
}
