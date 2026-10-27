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
 * The development entry's `trace-export` (ADR-46): a pipeline with tracing runs in the IDE without
 * any definition, the spans are accepted and nothing leaves the machine.
 */
class DevTraceExportTest {
  @TempDir lateinit var tmp: Path

  private val buffer = ByteArrayOutputStream()
  private val output
    get() = buffer.toString(Charsets.UTF_8)

  private val declared =
      """, typedResources = {@TypedResource(name = "trace", type = "trace-export")}"""

  private fun execute(resources: String?, spans: Int): Int {
    val body =
        """
        for (int i = 0; i < $spans; i++) {
          context.getAccessors().traceExport("trace").submit(
              new SpanRecord("step", java.time.Instant.now(), java.time.Instant.now(),
                  SpanStatus.OK, null, java.util.Map.of("rows", i), "s" + i, null));
        }
        context.getFiles().writeText(FileScope.PIPELINE_SHARED, "done", "x");
        """
    val jar =
        PipelineJars.build(
            tmp,
            "T.jar",
            mapOf("T" to PipelineJars.pipeline("T", "t", body, "", declared)),
        )
    val env = buildMap {
      put("RUNLINE_ALLOW_LIST", "java.lang,java.util,java.io,java.time")
      resources?.let { put("RUNLINE_RESOURCES", it) }
    }
    val config =
        DevConfig.fromEnvironment(env, tmp.resolve("project"))
            .copy(waitLimit = Duration.ofSeconds(30))
    return DevSession(config, PrintStream(buffer, true, Charsets.UTF_8))
        .execute(DevArguments(jar, "T", emptyMap()), "dev-trace")
  }

  @Test
  fun `a pipeline that submits spans runs with no definition of the role and the spans are counted`() {
    val code = execute(resources = null, spans = 3)

    assertEquals(DevSession.EXIT_OK, code, output)
    assertTrue("trace" in output && "3 span" in output && "not exported" in output, output)
  }

  @Test
  fun `the role may be listed in RUNLINE_RESOURCES as trace-export and is used the same`() {
    val code = execute(resources = "trace=trace-export", spans = 2)

    assertEquals(DevSession.EXIT_OK, code, output)
    assertTrue("2 span" in output, output)
  }
}
