package dev.lawlan.runline.engine.trigger

import dev.lawlan.runline.engine.artifact.ParameterDoc
import dev.lawlan.runline.engine.run.ParameterCheck
import dev.lawlan.runline.engine.run.ParameterProblem
import dev.lawlan.runline.engine.run.ParameterProblemKind
import dev.lawlan.runline.engine.run.validateParameters

/**
 * Checks the parameters of a trigger against the version it is bound to (ADR-005, ADR-036): the
 * [fixed] ones and the names the [caller] provides with each call must all be declared, none may be
 * both, and a required parameter without a default must be one of them. [ParameterCheck.Valid] then
 * carries what every run gets whatever the caller says: the fixed parameters and the defaults of
 * optional ones nobody provides.
 */
fun checkBinding(
    declared: List<ParameterDoc>,
    fixed: Map<String, String>,
    caller: Collection<String>,
): ParameterCheck {
  // The caller's names stand in with a blank value, so that they count as supplied.
  val check = validateParameters(declared, fixed + caller.associateWith { "" })
  val both =
      declared
          .map { it.name }
          .filter { it in caller && it in fixed }
          .map { ParameterProblem(it, ParameterProblemKind.FIXED_AND_CALLER) }
  return when {
    check is ParameterCheck.Invalid -> ParameterCheck.Invalid(check.problems + both)
    both.isNotEmpty() -> ParameterCheck.Invalid(both)
    else -> ParameterCheck.Valid((check as ParameterCheck.Valid).effective - caller.toSet())
  }
}
