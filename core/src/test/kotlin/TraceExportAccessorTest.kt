package dev.lawlan.runline.core

import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

/**
 * The accessor of a `trace-export` resource as a pipeline sees it (ADR-46): a finished span is
 * submitted as one call to the host with JDK types only, and nothing a pipeline does with it can
 * make the run fail. The host here is a function: it is the boundary of the run, not an external
 * system.
 */
class TraceExportAccessorTest {
  @TempDir lateinit var tmp: Path

  private val seen = mutableListOf<Map<String, Any?>>()
  private var answer: Map<String, Any?> = mapOf("ok" to true, "value" to null)

  private fun context(
      declared: Map<String, String> = mapOf("trace" to "trace-export"),
      provided: Map<String, String> = declared,
  ): PipelineContext {
    val metadata =
        PipelineMetadata(
            "demo",
            emptyList(),
            emptyMap(),
            AccessPolicy.Allow(emptySet()),
            AccessPolicy.Allow(emptySet()),
            declared.keys,
            declared,
        )
    return RestrictedContext(
        metadata,
        emptyMap(),
        tmp.resolve("shared").createDirectories(),
        tmp.resolve("run").createDirectories(),
        resources =
            ResourceLink(provided) {
              seen += it
              answer
            },
    )
  }

  private val start = Instant.parse("2026-10-27T10:00:00Z")

  private fun span(
      attributes: Map<String, Any?> = emptyMap(),
      parentId: String? = null,
      status: SpanStatus = SpanStatus.OK,
      statusMessage: String? = null,
  ) =
      SpanRecord(
          "step",
          start,
          start.plusMillis(5),
          status,
          statusMessage,
          attributes,
          "s1",
          parentId,
      )

  @Test
  fun `a finished span becomes one call to the host with JDK types only`() {
    context().accessors.traceExport("trace").submit(span(mapOf("rows" to 3), parentId = "root"))

    val call = seen.single()
    assertEquals("trace", call["resource"])
    assertEquals("trace.submit", call["operation"])
    @Suppress("UNCHECKED_CAST") val arguments = call["arguments"] as Map<String, Any?>
    assertEquals("step", arguments["name"])
    assertEquals(start, arguments["startTime"])
    assertEquals(start.plusMillis(5), arguments["endTime"])
    assertEquals("OK", arguments["status"])
    assertEquals("s1", arguments["id"])
    assertEquals("root", arguments["parentId"])
    assertEquals(mapOf("rows" to 3L), arguments["attributes"])
    val types = arguments.values.filterNotNull().map { it.javaClass.name }
    assertTrue(types.all { it.startsWith("java.") }, types.toString())
  }

  @Test
  fun `attributes of the allowed kinds are carried and any other is dropped and counted`() {
    class Odd

    val attributes =
        mapOf(
            "text" to "a",
            "flag" to true,
            "small" to 1.toShort(),
            "int" to 2,
            "long" to 3L,
            "float" to 1.5f,
            "double" to 2.5,
            "texts" to listOf("a", "b"),
            "numbers" to listOf(1, 2),
            "none" to emptyList<String>(),
            "null" to null,
            "object" to Odd(),
            "mixed" to listOf("a", 1),
            "nested" to listOf(listOf("a")),
            "map" to mapOf("k" to "v"),
            "oddList" to listOf(Odd()),
        )

    val dropped = context().accessors.traceExport("trace").submit(span(attributes))

    @Suppress("UNCHECKED_CAST")
    val sent = (seen.single()["arguments"] as Map<String, Any?>)["attributes"] as Map<String, Any?>
    assertEquals(
        mapOf(
            "text" to "a",
            "flag" to true,
            "small" to 1L,
            "int" to 2L,
            "long" to 3L,
            "float" to 1.5,
            "double" to 2.5,
            "texts" to listOf("a", "b"),
            "numbers" to listOf(1L, 2L),
            "none" to emptyList<String>(),
        ),
        sent,
    )
    assertEquals(6, dropped)
  }

  @Test
  fun `a declared role the host did not provide takes spans and does nothing`() {
    val accessor = context(provided = emptyMap()).accessors.traceExport("trace")

    assertEquals(0, accessor.submit(span(mapOf("rows" to 3))))

    assertEquals(emptyList(), seen)
  }

  @Test
  fun `a host that fails or throws does not fail the submission`() {
    val failing = context().accessors.traceExport("trace")
    answer = mapOf("ok" to false, "failure" to "ENDED")
    failing.submit(span())

    val throwing =
        RestrictedContext(
                PipelineMetadata(
                    "demo",
                    emptyList(),
                    emptyMap(),
                    AccessPolicy.Allow(emptySet()),
                    AccessPolicy.Allow(emptySet()),
                    setOf("trace"),
                    mapOf("trace" to "trace-export"),
                ),
                emptyMap(),
                tmp.resolve("shared").createDirectories(),
                tmp.resolve("run").createDirectories(),
                resources =
                    ResourceLink(mapOf("trace" to "trace-export")) {
                      throw IllegalStateException("host is gone")
                    },
            )
            .accessors
            .traceExport("trace")
    throwing.submit(span())

    assertEquals(1, seen.size)
  }

  @Test
  fun `a role that was not declared, declared by name only, or of another type is refused`() {
    fun refusal(declared: Map<String, String>, resources: Set<String> = declared.keys) =
        assertFailsWith<ResourceAccessException> {
              val metadata =
                  PipelineMetadata(
                      "demo",
                      emptyList(),
                      emptyMap(),
                      AccessPolicy.Allow(emptySet()),
                      AccessPolicy.Allow(emptySet()),
                      resources,
                      declared,
                  )
              RestrictedContext(
                      metadata,
                      emptyMap(),
                      tmp.resolve("shared").createDirectories(),
                      tmp.resolve("run").createDirectories(),
                  )
                  .accessors
                  .traceExport("trace")
            }
            .failure

    assertEquals(ResourceFailure.NOT_DECLARED, refusal(emptyMap()))
    assertEquals(ResourceFailure.NO_TYPE_DECLARED, refusal(emptyMap(), setOf("trace")))
    assertEquals(ResourceFailure.TYPE_MISMATCH, refusal(mapOf("trace" to "file")))
  }
}
