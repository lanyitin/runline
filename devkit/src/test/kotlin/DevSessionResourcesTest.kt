package dev.lawlan.runline.devkit

import dev.lawlan.runline.devkit.support.PipelineJars
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * What the development entry says and does when the resources a pipeline declares are not there.
 */
class DevSessionResourcesTest {
  @TempDir lateinit var tmp: Path

  private val buffer = ByteArrayOutputStream()
  private val output
    get() = buffer.toString(Charsets.UTF_8)

  private fun execute(resources: String?, declared: String): Int {
    val jar =
        PipelineJars.build(
            tmp,
            "P.jar",
            mapOf(
                "P" to
                    PipelineJars.pipeline(
                        "P",
                        "p",
                        """context.getAccessors().file("log").writeText("x");""",
                        "",
                        declared,
                    )
            ),
        )
    val env = buildMap {
      put("RUNLINE_ALLOW_LIST", "java.lang,java.util,java.io")
      resources?.let { put("RUNLINE_RESOURCES", it) }
    }
    val config =
        DevConfig.fromEnvironment(env, tmp.resolve("project"))
            .copy(waitLimit = Duration.ofSeconds(30))
    return DevSession(config, PrintStream(buffer, true, Charsets.UTF_8))
        .execute(DevArguments(jar, "P", emptyMap()), "dev-1")
  }

  private val typedFile = """, typedResources = {@TypedResource(name = "log", type = "file")}"""

  @Test
  fun `a typed resource the project does not define is said so and nothing runs`() {
    val code = execute(resources = null, declared = typedFile)

    assertEquals(DevSession.EXIT_NOT_STARTED, code)
    assertTrue("RUNLINE_RESOURCES" in output && "log" in output, output)
    assertTrue("[stdout]" !in output)
  }

  @Test
  fun `a resource defined as another type than the pipeline declares is said so and nothing runs`() {
    val code = execute(resources = "log=counter", declared = typedFile)

    assertEquals(DevSession.EXIT_NOT_STARTED, code)
    assertTrue("log" in output && "counter" in output && "file" in output, output)
  }

  @Test
  fun `a defined file resource is acquired and released on the console`() {
    val code = execute(resources = "log=file:out.txt", declared = typedFile)

    assertEquals(DevSession.EXIT_OK, code, output)
    assertTrue("acquired locally: log" in output && "released: log" in output, output)
  }

  @Test
  fun `a file resource whose path cannot be used is said so and nothing runs`() {
    java.nio.file.Files.createDirectories(tmp.resolve("project/.runline/resources"))
    java.nio.file.Files.writeString(
        tmp.resolve("project/.runline/resources/d"),
        "a file where a directory should be",
    )

    val code = execute(resources = "log=file:d/out.txt", declared = typedFile)

    assertEquals(DevSession.EXIT_NOT_STARTED, code)
    assertTrue("log" in output && "cannot be used" in output, output)
    assertTrue("[stdout]" !in output)
  }
}
