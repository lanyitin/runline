package dev.lawlan.runline.engine.info

import java.io.StringReader
import java.time.Instant
import java.util.Properties
import kotlin.test.*

class BuildInfoTest {
  private fun properties(text: String) = Properties().apply { load(StringReader(text)) }

  private val valid =
      """
      version=1.0.0
      commitHash=${"a".repeat(40)}
      dirty=false
      buildTime=2026-10-05T21:14:52Z
      """
          .trimIndent()

  @Test
  fun `the build info is read from what the build wrote`() {
    val info = BuildInfo.parse(properties(valid))

    assertEquals(
        BuildInfo("1.0.0", "a".repeat(40), false, Instant.parse("2026-10-05T21:14:52Z")),
        info,
    )
  }

  @Test
  fun `a dirty build is read as dirty`() {
    val info = BuildInfo.parse(properties(valid.replace("dirty=false", "dirty=true")))

    assertTrue(info.dirty)
  }

  @Test
  fun `a missing entry fails naming it`() {
    for (key in listOf("version", "commitHash", "dirty", "buildTime")) {
      val without = valid.lines().filterNot { it.startsWith("$key=") }.joinToString("\n")
      val failure = assertFailsWith<IllegalStateException> { BuildInfo.parse(properties(without)) }
      assertTrue(failure.message!!.contains(key), failure.message)
    }
  }

  @Test
  fun `a value that is not valid fails naming the entry`() {
    val badTime = valid.replace("2026-10-05T21:14:52Z", "yesterday")
    val badDirty = valid.replace("dirty=false", "dirty=maybe")

    assertTrue(
        assertFailsWith<IllegalStateException> { BuildInfo.parse(properties(badTime)) }
            .message!!
            .contains("buildTime")
    )
    assertTrue(
        assertFailsWith<IllegalStateException> { BuildInfo.parse(properties(badDirty)) }
            .message!!
            .contains("dirty")
    )
  }
}
