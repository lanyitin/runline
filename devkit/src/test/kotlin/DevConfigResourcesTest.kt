package dev.lawlan.runline.devkit

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The typed shared resources of a development run come from the environment (ADR-019). */
class DevConfigResourcesTest {
  private val project = Path.of("/work/my-pipelines")

  private fun config(vararg env: Pair<String, String>) =
      DevConfig.fromEnvironment(env.toMap(), project).resources

  @Test
  fun `the root of resource files defaults to a place inside the project`() {
    assertEquals(project.resolve(".runline/resources"), config().root)
    assertEquals(emptyMap(), config().definitions)
  }

  @Test
  fun `the root can be moved`() {
    assertEquals(Path.of("/data/res"), config("RUNLINE_RESOURCE_ROOT" to "/data/res").root)
    assertEquals(project.resolve("res"), config("RUNLINE_RESOURCE_ROOT" to "res").root)
  }

  @Test
  fun `resources are defined by name, type and for a file its path`() {
    val definitions =
        config("RUNLINE_RESOURCES" to "log=file:logs/out.txt, gate=counter").definitions

    assertEquals(
        mapOf(
            "log" to LocalResource("file", "logs/out.txt"),
            "gate" to LocalResource("counter", null),
        ),
        definitions,
    )
  }

  @Test
  fun `a definition that cannot be read fails fast naming the variable`() {
    listOf(
            "log",
            "log=",
            "log=file",
            "log=file:",
            "log=tape:x",
            "log=counter:x",
            "a=counter,a=counter",
            "=file:x",
        )
        .forEach {
          val e = assertFailsWith<IllegalStateException>(it) { config("RUNLINE_RESOURCES" to it) }
          assertEquals(true, e.message!!.contains("RUNLINE_RESOURCES"), e.message)
        }
  }
}
