package dev.lawlan.runline.core

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

/**
 * What a pipeline can reach through `context.accessors` (ADR-019): only a resource it declared
 * together with a type, of that type, and that the host provided. Nothing here needs a host: every
 * refusal happens before the host is asked.
 */
class AccessorRulesTest {
  @TempDir lateinit var tmp: Path

  private fun context(
      resources: Set<String>,
      types: Map<String, String>,
      provided: Map<String, String> = emptyMap(),
  ): PipelineContext {
    val metadata =
        PipelineMetadata(
            "demo",
            emptyList(),
            emptyMap(),
            AccessPolicy.Allow(emptySet()),
            AccessPolicy.Allow(emptySet()),
            resources,
            types,
        )
    return RestrictedContext(
        metadata,
        emptyMap(),
        tmp.resolve("shared").createDirectories(),
        tmp.resolve("run").createDirectories(),
        resources =
            ResourceLink(provided) { error("the host must not be asked when the rules refuse") },
    )
  }

  private fun refused(context: PipelineContext, name: String): ResourceAccessException =
      assertFailsWith<ResourceAccessException> { context.accessors.file(name) }

  @Test
  fun `a resource the pipeline did not declare is refused`() {
    val e = refused(context(emptySet(), emptyMap()), "log")

    assertEquals(ResourceFailure.NOT_DECLARED, e.failure)
    assertEquals("log", e.resource)
  }

  @Test
  fun `a resource declared by name only gives capacity and no accessor`() {
    val e = refused(context(setOf("log"), emptyMap(), mapOf("log" to "file")), "log")

    assertEquals(ResourceFailure.NO_TYPE_DECLARED, e.failure)
  }

  @Test
  fun `a resource declared with another type than the one asked for is refused`() {
    val ctx = context(setOf("log"), mapOf("log" to "jdbc-pool"), mapOf("log" to "jdbc-pool"))

    assertEquals(ResourceFailure.TYPE_MISMATCH, refused(ctx, "log").failure)
  }

  @Test
  fun `a declared file resource the host did not provide is refused`() {
    val ctx = context(setOf("log"), mapOf("log" to "file"), provided = emptyMap())

    assertEquals(ResourceFailure.NOT_PROVIDED, refused(ctx, "log").failure)
  }

  @Test
  fun `the message names the resource and the reason and nothing else`() {
    val e = refused(context(emptySet(), emptyMap()), "log")

    assertTrue(e.message!!.contains("log"), e.message)
    assertTrue(e.message!!.contains("NOT_DECLARED"), e.message)
  }
}
