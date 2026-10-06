package dev.lawlan.runline.analyzer

import demo.KotlinMetadataPipeline
import java.io.IOException
import java.lang.classfile.Annotation
import java.lang.classfile.AnnotationElement
import java.lang.classfile.ClassFile
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute
import java.lang.constant.ClassDesc
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class PipelineMetadataReadingTest {
  @TempDir lateinit var tmp: Path

  private val allowList = AllowList("v1", listOf(AllowListEntry("java.lang")))

  private fun report(path: Path) = SafetyAnalyzer().analyze(path, allowList)

  private fun jar(
      vararg sources: Pair<String, String>,
      extra: Map<String, ByteArray> = emptyMap(),
  ): Path =
      CompiledJars.build(tmp, "p-${System.nanoTime()}.jar", sources.toMap(), extraEntries = extra)

  private fun metadataOf(path: Path) = report(path).pipelines.single().metadata

  @Test
  fun `reads parameters, files, limits and resources without running anything`() {
    val path =
        jar(
            "demo.Nightly" to
                CompiledJars.pipeline(
                    "demo.Nightly",
                    limits =
                        """
                        parameters = {
                          @Param(name = "env"),
                          @Param(name = "flag", required = false)
                        },
                        files = {
                          @FileAccess(scope = FileScope.PIPELINE_SHARED),
                          @FileAccess(scope = FileScope.RUN_PRIVATE, mode = FileMode.READ_WRITE)
                        },
                        network = @AccessLimit(allow = {"example.com", "api.example.org"}),
                        processes = @AccessLimit(unrestricted = true),
                        resources = {"db-lock"}
                        """
                            .trimIndent(),
                    // Would fail loudly if the class were ever initialised.
                    members =
                        "static { if (true) throw new IllegalStateException(\"initialised\"); }",
                )
        )

    val m = metadataOf(path)

    assertEquals("Nightly-pipeline", m.name)
    assertEquals(
        listOf(ParameterMetadata("env", true, null), ParameterMetadata("flag", false, "")),
        m.parameters,
    )
    assertEquals(
        listOf(
            FileAccessMetadata("PIPELINE_SHARED", "READ_ONLY"),
            FileAccessMetadata("RUN_PRIVATE", "READ_WRITE"),
        ),
        m.files,
    )
    assertEquals(AccessLimitMetadata(false, listOf("example.com", "api.example.org")), m.network)
    assertEquals(AccessLimitMetadata(true, emptyList()), m.processes)
    assertEquals(listOf("db-lock"), m.resources)
  }

  @Test
  fun `reads a pipeline compiled from Kotlin with parameter defaults`() {
    val path = KotlinFixtures.jarOf(tmp, "k.jar", KotlinMetadataPipeline::class.java)

    val m = metadataOf(path)

    assertEquals("kotlin-metadata", m.name)
    assertEquals(
        listOf(ParameterMetadata("env", true, null), ParameterMetadata("retries", false, "3")),
        m.parameters,
    )
    assertEquals(AccessLimitMetadata(false, listOf("example.com")), m.network)
    assertEquals(AccessLimitMetadata(false, emptyList()), m.processes)
    assertEquals(listOf(FileAccessMetadata("RUN_PRIVATE", "READ_WRITE")), m.files)
    assertEquals(listOf("db-lock"), m.resources)
  }

  @Test
  fun `members that are not written take the declared defaults`() {
    val m = metadataOf(jar("Bare" to CompiledJars.pipeline("Bare", limits = "")))

    assertEquals(emptyList(), m.parameters)
    assertEquals(emptyList(), m.files)
    assertEquals(emptyList(), m.resources)
    assertEquals(AccessLimitMetadata(true, emptyList()), m.network)
    assertEquals(AccessLimitMetadata(true, emptyList()), m.processes)
  }

  @Test
  fun `an access limit without the unrestricted flag is an allow list`() {
    val m =
        metadataOf(jar("Lim" to CompiledJars.pipeline("Lim", limits = "network = @AccessLimit")))

    assertEquals(AccessLimitMetadata(false, emptyList()), m.network)
  }

  @Test
  fun `the verdict and the metadata come from the same analysis`() {
    val pipeline =
        report(jar("Bare" to CompiledJars.pipeline("Bare", limits = ""))).pipelines.single()

    assertEquals(
        listOf(
            UnsafeReason.UnrestrictedAccess(IoCategory.NETWORK),
            UnsafeReason.UnrestrictedAccess(IoCategory.PROCESSES),
        ),
        pipeline.reasons,
    )
    assertEquals(AccessLimitMetadata(true), pipeline.metadata.network)
    assertEquals("Bare-pipeline", pipeline.pipelineName)
  }

  @Test
  fun `a class without a pipeline definition is not a pipeline`() {
    val report = report(jar("Plain" to "public class Plain {}"))

    assertEquals(emptyList(), report.pipelines)
    assertEquals(emptyList(), report.metadataProblems)
  }

  @Test
  fun `an unparsable class file is listed as unreadable`() {
    val report =
        report(
            jar(
                "Ok" to CompiledJars.pipeline("Ok"),
                extra = mapOf("demo/Broken.class" to "junk".toByteArray()),
            )
        )

    assertEquals(listOf("demo.Broken"), report.unreadableClasses)
  }

  @Test
  fun `a pipeline whose annotation content is unexpected is reported as a metadata problem`() {
    val bad = annotatedClass("demo.Bad", "parameters" to "not an array")

    val report = report(jar(extra = mapOf("demo/Bad.class" to bad)))

    assertEquals(emptyList(), report.pipelines)
    val problem = report.metadataProblems.single()
    assertEquals("demo.Bad", problem.className)
    assertTrue(problem.detail.startsWith("unexpected annotation content"), problem.detail)
  }

  @Test
  fun `finds core classes bundled in the jar`() {
    val path =
        jar(
            "demo.P" to CompiledJars.pipeline("demo.P"),
            extra =
                mapOf(
                    "dev/lawlan/runline/core/Pipeline.class" to ByteArray(0),
                    "dev/lawlan/runline/core/sub/Deep.class" to ByteArray(0),
                    "META-INF/versions/21/dev/lawlan/runline/core/Other.class" to ByteArray(0),
                ),
        )

    assertEquals(
        listOf(
                "dev.lawlan.runline.core.Pipeline",
                "dev.lawlan.runline.core.sub.Deep",
                "dev.lawlan.runline.core.Other",
            )
            .sorted(),
        report(path).bundledCoreClasses.sorted(),
    )
  }

  @Test
  fun `similar package names and non class entries are not core`() {
    val path =
        jar(
            "demo.P" to CompiledJars.pipeline("demo.P"),
            extra =
                mapOf(
                    "dev/lawlan/runline/corelib/X.class" to ByteArray(0),
                    "dev/lawlan/runline/core.txt" to ByteArray(0),
                    "dev/lawlan/runline/core/README.md" to ByteArray(0),
                    "other/dev/lawlan/runline/core/Y.class" to ByteArray(0),
                ),
        )

    assertEquals(emptyList(), report(path).bundledCoreClasses)
  }

  @Test
  fun `a file that is not a zip cannot be analysed`() {
    val notZip = Files.write(tmp.resolve("x.jar"), "hello".toByteArray())

    assertFailsWith<IOException> { report(notZip) }
  }

  /** A real class file annotated with `@PipelineDefinition` whose members are plain strings. */
  private fun annotatedClass(name: String, vararg members: Pair<String, String>): ByteArray {
    val annotation =
        Annotation.of(
            ClassDesc.ofDescriptor("Ldev/lawlan/runline/core/PipelineDefinition;"),
            listOf(AnnotationElement.ofString("name", "bad")) +
                members.map { (k, v) -> AnnotationElement.ofString(k, v) },
        )
    return ClassFile.of().build(ClassDesc.of(name)) { builder ->
      builder.with(RuntimeVisibleAnnotationsAttribute.of(annotation))
    }
  }
}
