package dev.lawlan.runline.core

import dev.lawlan.runline.core.fixtures.*
import kotlin.test.*

class PipelineMetadataTest {

  @Test
  fun `reads declared name, parameters, files, limits and resources`() {
    val meta = PipelineMetadataReader.read(FullPipeline::class.java)

    assertEquals("full", meta.name)
    assertEquals(
        listOf(ParameterSpec("env", true, null), ParameterSpec("retries", false, "3")),
        meta.parameters,
    )
    assertEquals(
        mapOf(
            FileScope.PIPELINE_SHARED to FileMode.READ_ONLY,
            FileScope.RUN_PRIVATE to FileMode.READ_WRITE,
        ),
        meta.files,
    )
    assertEquals(AccessPolicy.Allow(setOf("api.example.com")), meta.network)
    assertEquals(AccessPolicy.Allow(setOf("git")), meta.processes)
    assertEquals(setOf("lemonade"), meta.resources)
  }

  @Test
  fun `a typed resource is a declared resource name and carries the type the pipeline expects`() {
    val meta = PipelineMetadataReader.read(TypedResourcesPipeline::class.java)

    assertEquals(listOf("lock", "shared-file", "llm", "odd"), meta.resources.toList())
    assertEquals(
        mapOf("shared-file" to "file", "llm" to "openai-compatible", "odd" to "not-a-type"),
        meta.resourceTypes,
    )
  }

  @Test
  fun `a pipeline that declares only names declares no type`() {
    assertEquals(emptyMap(), PipelineMetadataReader.read(FullPipeline::class.java).resourceTypes)
  }

  @Test
  fun `the closed set of resource types is exactly the four of ADR-019`() {
    assertEquals(setOf("counter", "file", "jdbc-pool", "openai-compatible"), ResourceTypes.ALL)
  }

  @Test
  fun `unprovided network and process limits mean unrestricted, and no file scope is available`() {
    val meta = PipelineMetadataReader.read(MinimalPipeline::class.java)

    assertEquals(AccessPolicy.Unrestricted, meta.network)
    assertEquals(AccessPolicy.Unrestricted, meta.processes)
    assertEquals(emptyMap(), meta.files)
    assertEquals(emptySet(), meta.resources)
  }

  @Test
  fun `reading metadata does not run pipeline logic or static initializers`() {
    val loaded = Class.forName(StaticInitPipeline::class.java.name, false, javaClass.classLoader)

    PipelineMetadataReader.read(loaded)

    assertFalse(InitProbe.initialized)
  }

  @Test
  fun `a class without a pipeline declaration is rejected`() {
    val e =
        assertFailsWith<IllegalArgumentException> {
          PipelineMetadataReader.read(NotAPipeline::class.java)
        }
    assertTrue(e.message!!.contains(NotAPipeline::class.java.name))
  }
}
