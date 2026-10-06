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
