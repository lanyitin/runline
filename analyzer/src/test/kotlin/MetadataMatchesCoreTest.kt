package dev.lawlan.runline.analyzer

import demo.KotlinEdgeMetadataPipeline
import demo.KotlinMetadataPipeline
import dev.lawlan.runline.core.AccessPolicy
import dev.lawlan.runline.core.PipelineDefinition
import dev.lawlan.runline.core.PipelineMetadataReader
import dev.lawlan.runline.core.View
import java.net.URLClassLoader
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

/**
 * The analyzer reads metadata from class file bytes; core reads it by reflection from the loaded
 * class. Both must agree for the same compiled pipeline, so the defaults cannot drift apart. This
 * is the only place that sees both; the analyzer's main code does not depend on core.
 */
class MetadataMatchesCoreTest {
  @TempDir lateinit var tmp: Path

  private val allowList = AllowList("v1", listOf(AllowListEntry("java.lang")))

  private fun assertMatchesCore(jar: Path, className: String) {
    val fromAnalyzer =
        SafetyAnalyzer()
            .analyze(jar, allowList)
            .pipelines
            .single { it.className == className }
            .metadata
    val (fromCore, viewsFromCore) =
        URLClassLoader(arrayOf(jar.toUri().toURL()), javaClass.classLoader).use { loader ->
          // Not initialised: reading the annotation never runs the class.
          val type = Class.forName(className, false, loader)
          PipelineMetadataReader.read(type) to
              type.getAnnotation(PipelineDefinition::class.java).views.map { it.toExpected() }
        }

    assertEquals(fromCore.name, fromAnalyzer.name, "name")
    assertEquals(
        fromCore.parameters.map { ParameterMetadata(it.name, it.required, it.default) },
        fromAnalyzer.parameters,
        "parameters",
    )
    assertEquals(
        fromCore.files.map { (scope, mode) -> FileAccessMetadata(scope.name, mode.name) },
        fromAnalyzer.files,
        "files",
    )
    assertLimit(fromCore.network, fromAnalyzer.network, "network")
    assertLimit(fromCore.processes, fromAnalyzer.processes, "processes")
    assertEquals(fromCore.resources.toList(), fromAnalyzer.resources, "resources")
    assertEquals(fromCore.resourceTypes, fromAnalyzer.resourceTypes, "resource types")
    assertEquals(fromCore.resourcePurposes, fromAnalyzer.resourcePurposes, "resource purposes")
    // Views are not part of the metadata a run reads, so core's annotation is read directly.
    assertEquals(viewsFromCore, fromAnalyzer.views, "views")
  }

  private fun View.toExpected() =
      ViewMetadata(
          id,
          title,
          description,
          resource,
          query,
          parameters.map {
            ViewParameterMetadata(it.name, it.type.name, it.required, it.options.toList())
          },
          columns.map { column ->
            ViewColumnMetadata(
                column.name,
                column.label,
                column.type.name,
                column.tones.map { StatusToneMetadata(it.value, it.tone.name) },
                column.drill.firstOrNull()?.let {
                  DrillMetadata(it.view, it.parameter, it.from.ifEmpty { column.name })
                },
            )
          },
          sort.map { ViewSortMetadata(it.column, it.descending) },
      )

  /** An unrestricted limit has no allow list in core; only a restricted one carries entries. */
  private fun assertLimit(core: AccessPolicy, analyzer: AccessLimitMetadata, what: String) {
    when (core) {
      is AccessPolicy.Unrestricted -> assertEquals(true, analyzer.unrestricted, "$what flag")
      is AccessPolicy.Allow -> {
        assertEquals(false, analyzer.unrestricted, "$what flag")
        assertEquals(core.entries.toList(), analyzer.allow, "$what allow")
      }
    }
  }

  private fun assertDeclaration(definition: String) {
    val jar =
        CompiledJars.build(
            tmp,
            "p-${System.nanoTime()}.jar",
            mapOf("demo.P" to CompiledJars.pipeline("demo.P", limits = definition)),
        )
    assertMatchesCore(jar, "demo.P")
  }

  @Test fun `a pipeline that declares nothing but its name`() = assertDeclaration("")

  @Test
  fun `network and process limits in every written form`() {
    listOf(
            "network = @AccessLimit, processes = @AccessLimit",
            "network = @AccessLimit(unrestricted = true), processes = @AccessLimit(unrestricted = false)",
            "network = @AccessLimit(allow = {}), processes = @AccessLimit(allow = {\"git\", \"ls\"})",
            "network = @AccessLimit(allow = {\"a.example\", \"b.example\"})",
            "processes = @AccessLimit(unrestricted = true)",
            "network = @AccessLimit(unrestricted = true, allow = {\"ignored.example\"})",
        )
        .forEach { assertDeclaration(it) }
  }

  @Test
  fun `parameters that are required or optional`() {
    listOf(
            "parameters = {}",
            "parameters = { @Param(name = \"a\") }",
            "parameters = { @Param(name = \"a\", required = false) }",
            "parameters = { @Param(name = \"a\", required = true), @Param(name = \"b\", required = false) }",
        )
        .forEach { assertDeclaration(it) }
  }

  @Test
  fun `every file scope with every mode`() {
    listOf(
            "files = {}",
            "files = { @FileAccess(scope = FileScope.PIPELINE_SHARED) }",
            "files = { @FileAccess(scope = FileScope.RUN_PRIVATE) }",
            "files = { @FileAccess(scope = FileScope.PIPELINE_SHARED, mode = FileMode.READ_ONLY) }",
            "files = { @FileAccess(scope = FileScope.PIPELINE_SHARED, mode = FileMode.READ_WRITE) }",
            "files = { @FileAccess(scope = FileScope.RUN_PRIVATE, mode = FileMode.READ_ONLY) }",
            "files = { @FileAccess(scope = FileScope.RUN_PRIVATE, mode = FileMode.READ_WRITE) }",
            "files = { @FileAccess(scope = FileScope.RUN_PRIVATE, mode = FileMode.READ_WRITE), " +
                "@FileAccess(scope = FileScope.PIPELINE_SHARED) }",
        )
        .forEach { assertDeclaration(it) }
  }

  @Test
  fun `declared resources`() {
    listOf("resources = {}", "resources = {\"db\"}", "resources = {\"db\", \"queue\", \"mail\"}")
        .forEach { assertDeclaration(it) }
  }

  @Test
  fun `declared resource types, alone, beside names and outside the closed set`() {
    listOf(
            "typedResources = {}",
            "typedResources = { @TypedResource(name = \"f\", type = \"file\") }",
            "resources = {\"a\", \"f\"}, typedResources = { @TypedResource(name = \"f\", type = ResourceTypes.FILE) }",
            "typedResources = { @TypedResource(name = \"x\", type = \"not-a-type\"), " +
                "@TypedResource(name = \"c\", type = ResourceTypes.COUNTER) }",
            "resources = {\"r\"}, typedResources = { @TypedResource(name = \"r\", type = \"jdbc-pool\") }",
            "typedResources = { @TypedResource(name = \"p\", type = \"file\", purpose = \"What it is for\"), " +
                "@TypedResource(name = \"q\", type = \"file\", purpose = \"\"), " +
                "@TypedResource(name = \"s\", type = \"counter\") }",
        )
        .forEach { assertDeclaration(it) }
  }

  @Test
  fun `declared views, with every member written and with every default left out`() {
    listOf(
            "views = {}",
            "views = { @View(id = \"v\", title = \"V\", resource = \"db\", query = \"SELECT 1\", columns = {}) }",
            "typedResources = { @TypedResource(name = \"db\", type = ResourceTypes.JDBC_POOL) }, " +
                "views = { @View(id = \"a\", title = \"A\", description = \"d\", resource = \"db\", " +
                "query = \"SELECT 1\", " +
                "parameters = { @ViewParam(name = \"p\"), " +
                "@ViewParam(name = \"q\", type = ViewParamType.INTEGER, required = false), " +
                "@ViewParam(name = \"c\", type = ViewParamType.CHOICE, options = {\"x\", \"y\"}) }, " +
                "columns = { @ViewColumn(name = \"n\", label = \"N\"), " +
                "@ViewColumn(name = \"s\", label = \"S\", type = ViewColumnType.STATUS, " +
                "tones = { @StatusTone(value = \"ok\", tone = Tone.POSITIVE) }, " +
                "drill = @Drill(view = \"a\", parameter = \"p\")), " +
                "@ViewColumn(name = \"t\", label = \"T\", type = ViewColumnType.TIME, " +
                "drill = @Drill(view = \"a\", parameter = \"q\", from = \"n\")) }, " +
                "sort = { @ViewSort(column = \"n\"), @ViewSort(column = \"t\", descending = true) }) }",
        )
        .forEach { assertDeclaration(it) }
  }

  @Test
  fun `a pipeline declaring everything at once`() =
      assertDeclaration(
          "parameters = { @Param(name = \"env\"), @Param(name = \"n\", required = false) }, " +
              "files = { @FileAccess(scope = FileScope.RUN_PRIVATE, mode = FileMode.READ_WRITE) }, " +
              "network = @AccessLimit(allow = {\"x.example\"}), " +
              "processes = @AccessLimit(unrestricted = true), resources = {\"r\"}"
      )

  @Test
  fun `Kotlin pipelines with parameter defaults`() {
    val jar =
        KotlinFixtures.jarOf(
            tmp,
            "k.jar",
            KotlinMetadataPipeline::class.java,
            KotlinEdgeMetadataPipeline::class.java,
        )

    assertMatchesCore(jar, KotlinMetadataPipeline::class.java.name)
    assertMatchesCore(jar, KotlinEdgeMetadataPipeline::class.java.name)
  }
}
