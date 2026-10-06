package dev.lawlan.runline.devkit

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DevArgumentsTest {
  @Test
  fun `takes the jar, the pipeline class and key=value parameters`() {
    val parsed = DevArguments.parse(listOf("build/libs/p.jar", "demo.Hello", "who=world", "n=a=b"))

    assertEquals(Path.of("build/libs/p.jar"), parsed.jar)
    assertEquals("demo.Hello", parsed.pipelineClass)
    assertEquals(mapOf("who" to "world", "n" to "a=b"), parsed.parameters)
  }

  @Test
  fun `parameters are optional`() {
    assertEquals(emptyMap(), DevArguments.parse(listOf("p.jar", "demo.Hello")).parameters)
  }

  @Test
  fun `missing jar or class is rejected with a usage line`() {
    val e = assertFailsWith<IllegalArgumentException> { DevArguments.parse(listOf("p.jar")) }

    assertTrue("Usage" in e.message!!)
  }

  @Test
  fun `a parameter without a name or an equals sign is rejected`() {
    assertFailsWith<IllegalArgumentException> { DevArguments.parse(listOf("p.jar", "C", "who")) }
    assertFailsWith<IllegalArgumentException> { DevArguments.parse(listOf("p.jar", "C", "=x")) }
  }
}
