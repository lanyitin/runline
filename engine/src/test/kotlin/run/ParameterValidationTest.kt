package dev.lawlan.runline.engine.run

import dev.lawlan.runline.engine.artifact.ParameterDoc
import kotlin.test.*

class ParameterValidationTest {
  private val declared =
      listOf(
          ParameterDoc("env", required = true),
          ParameterDoc("retries", required = false, default = "3"),
          ParameterDoc("note", required = false),
      )

  @Test
  fun `supplied values are kept and omitted optionals take their default`() {
    val check = validateParameters(declared, mapOf("env" to "prod"))

    assertEquals(
        ParameterCheck.Valid(mapOf("env" to "prod", "retries" to "3")),
        check,
    )
  }

  @Test
  fun `a supplied optional overrides its default`() {
    val check = validateParameters(declared, mapOf("env" to "prod", "retries" to "9"))

    assertEquals(
        ParameterCheck.Valid(mapOf("env" to "prod", "retries" to "9")),
        check,
    )
  }

  @Test
  fun `a missing required parameter is refused and named`() {
    val check = validateParameters(declared, emptyMap())

    assertEquals(
        ParameterCheck.Invalid(listOf(ParameterProblem("env", ParameterProblemKind.MISSING))),
        check,
    )
  }

  @Test
  fun `an undeclared parameter is refused and named`() {
    val check = validateParameters(declared, mapOf("env" to "prod", "colour" to "red"))

    assertEquals(
        ParameterCheck.Invalid(listOf(ParameterProblem("colour", ParameterProblemKind.UNDECLARED))),
        check,
    )
  }

  @Test
  fun `every problem is reported, not only the first`() {
    val check = validateParameters(declared, mapOf("b" to "1", "a" to "2"))

    assertEquals(
        ParameterCheck.Invalid(
            listOf(
                ParameterProblem("env", ParameterProblemKind.MISSING),
                ParameterProblem("a", ParameterProblemKind.UNDECLARED),
                ParameterProblem("b", ParameterProblemKind.UNDECLARED),
            )
        ),
        check,
    )
  }

  @Test
  fun `a pipeline without parameters accepts none`() {
    assertEquals(ParameterCheck.Valid(emptyMap()), validateParameters(emptyList(), emptyMap()))
    assertIs<ParameterCheck.Invalid>(validateParameters(emptyList(), mapOf("x" to "1")))
  }
}
