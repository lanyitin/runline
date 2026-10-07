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

  // ---- openai-compatible ----

  private val typedOpenAi =
      """, typedResources = {@TypedResource(name = "lemon", type = "openai-compatible")}"""

  private fun executeOpenAi(settingsFile: String?, env: Map<String, String> = emptyMap()): Int {
    val project = tmp.resolve("project")
    settingsFile?.let {
      java.nio.file.Files.createDirectories(project.resolve("openai"))
      java.nio.file.Files.writeString(project.resolve("openai/lemon.json"), it)
    }
    val jar =
        PipelineJars.build(
            tmp,
            "Q.jar",
            mapOf(
                "Q" to
                    PipelineJars.pipeline(
                        "Q",
                        "q",
                        """context.getFiles().writeText(FileScope.PIPELINE_SHARED, "entered", "x");""",
                        "",
                        typedOpenAi,
                    )
            ),
        )
    val all =
        mapOf(
            "RUNLINE_ALLOW_LIST" to "java.lang,java.util,java.io",
            "RUNLINE_RESOURCES" to "lemon=openai-compatible:openai/lemon.json",
        ) + env
    val config = DevConfig.fromEnvironment(all, project).copy(waitLimit = Duration.ofSeconds(30))
    return DevSession(config, PrintStream(buffer, true, Charsets.UTF_8))
        .execute(DevArguments(jar, "Q", emptyMap()), "dev-2")
  }

  @Test
  fun `a settings file that is missing, not JSON or not valid is said by category and nothing runs`() {
    val cases =
        mapOf(
            null to "cannot be read",
            "not json" to "not a JSON object",
            """{"baseUrl":"ftp://x"}""" to "invalid_base_url",
            """{"baseUrl":"http://x/v1","apiKey":"sk-in-the-file"}""" to "invalid_settings",
            """{"baseUrl":"http://x/v1","secretAlias":"no good"}""" to "invalid_secret_alias",
        )
    for ((file, said) in cases) {
      buffer.reset()

      val code = executeOpenAi(file, mapOf("RUNLINE_SECRET_LEMON_KEY" to "sk-local-123"))

      assertEquals(DevSession.EXIT_NOT_STARTED, code, "$file")
      assertTrue(
          "lemon" in output && "RUNLINE_RESOURCES" in output && said in output,
          "$file: $output",
      )
      assertTrue("[stdout]" !in output)
      assertTrue("sk-local-123" !in output && "sk-in-the-file" !in output, output)
    }
  }
}
