package dev.lawlan.runline.core

import dev.lawlan.runline.core.fixtures.FullPipeline
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class ParametersTest {
  @TempDir lateinit var tmp: Path

  private val meta = PipelineMetadataReader.read(FullPipeline::class.java)

  private fun context(supplied: Map<String, String>) = RestrictedContext(meta, supplied, tmp, tmp)

  @Test
  fun `supplied values are visible and defaults fill optional parameters`() {
    assertEquals(
        mapOf("env" to "prod", "retries" to "3"),
        context(mapOf("env" to "prod")).parameters,
    )
    assertEquals(
        mapOf("env" to "prod", "retries" to "5"),
        context(mapOf("env" to "prod", "retries" to "5")).parameters,
    )
  }

  @Test
  fun `a missing required parameter is rejected, naming pipeline and parameter`() {
    val e = assertFailsWith<IllegalArgumentException> { context(emptyMap()) }
    assertTrue(e.message!!.contains("full") && e.message!!.contains("env"))
  }

  @Test
  fun `an undeclared parameter is rejected`() {
    val e =
        assertFailsWith<IllegalArgumentException> { context(mapOf("env" to "x", "bogus" to "1")) }
    assertTrue(e.message!!.contains("bogus"))
  }

  @Test
  fun `the context offers no operation to acquire shared resources`() {
    val members =
        PipelineContext::class.java.methods.map { it.name.lowercase() } +
            listOf(FileOperations::class, NetworkAccess::class, ProcessRunner::class).flatMap { c ->
              c.java.methods.map { it.name.lowercase() }
            }
    assertTrue(members.none { "resource" in it || "acquire" in it || "lock" in it })
  }
}
