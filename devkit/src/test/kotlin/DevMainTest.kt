package dev.lawlan.runline.devkit

import dev.lawlan.runline.devkit.support.PipelineJars
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class DevMainTest {
  @TempDir lateinit var tmp: Path

  private val buffer = ByteArrayOutputStream()
  private val out = PrintStream(buffer, true, Charsets.UTF_8)
  private val output
    get() = buffer.toString(Charsets.UTF_8)

  @Test
  fun `wrong arguments print the usage and run nothing`() {
    val code = DevMain.run(listOf("only-a-jar"), emptyMap(), tmp, out)

    assertEquals(2, code)
    assertTrue(DevArguments.USAGE in output, output)
  }

  @Test
  fun `a malformed setting fails fast with the variable named`() {
    val code =
        DevMain.run(listOf("p.jar", "C"), mapOf("RUNLINE_DEV_WAIT_SECONDS" to "soon"), tmp, out)

    assertEquals(2, code)
    assertTrue("RUNLINE_DEV_WAIT_SECONDS" in output, output)
  }

  @Test
  fun `runs a pipeline, each execution under its own generated run id`() {
    val jar =
        PipelineJars.build(
            tmp,
            "hello.jar",
            mapOf(
                "Hello" to
                    PipelineJars.pipeline(
                        "Hello",
                        "hello",
                        """System.out.println("on " + Thread.currentThread().getName());""",
                    )
            ),
        )
    val env = mapOf("RUNLINE_ALLOW_LIST" to "java.lang,java.io")

    assertEquals(0, DevMain.run(listOf(jar.toString(), "Hello"), env, tmp, out), output)
    assertEquals(0, DevMain.run(listOf(jar.toString(), "Hello"), env, tmp, out), output)

    val names =
        output.lines().filter { it.startsWith("[stdout] on ") }.map { it.substringAfter("on ") }
    assertEquals(2, names.size, output)
    assertTrue(names.all { it.matches(Regex("dev-[0-9]{8}-[0-9]{6}-[0-9a-f]{4}")) }, output)
    assertEquals(2, names.toSet().size, output)
  }
}
