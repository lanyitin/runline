package dev.lawlan.runline.engine.artifact

import dev.lawlan.runline.analyzer.AllowListEntry
import dev.lawlan.runline.analyzer.ClassEntry
import dev.lawlan.runline.analyzer.SafetyAnalyzer
import dev.lawlan.runline.analyzer.Verdict
import dev.lawlan.runline.engine.db.DatabaseMigrator
import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.support.PipelineJars
import dev.lawlan.runline.engine.support.PostgresTestContainer
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

class UploadServiceTest {
  private val database = PostgresTestContainer.newDatabase().also { DatabaseMigrator(it).migrate() }
  private val dataSource = dataSourceOf(database)
  private val store = PostgresArtifactStore(dataSource)
  private val dir: Path = Files.createTempDirectory("upload-service")
  private val staging = UploadStaging(Files.createTempDirectory(dir, "staging"))

  private val metricReader = InMemoryMetricReader.create()
  private val spans = InMemorySpanExporter.create()
  private val otel =
      OpenTelemetrySdk.builder()
          .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metricReader).build())
          .setTracerProvider(
              SdkTracerProvider.builder()
                  .addSpanProcessor(SimpleSpanProcessor.create(spans))
                  .build()
          )
          .build()

  private val now = Instant.parse("2026-10-04T12:00:00Z")
  private var allowList = listOf(AllowListEntry("java.lang"))
  private val service =
      UploadService(
          JarExpansionGuard(
              JarLimits(maxEntries = 100, maxEntryBytes = 1_000_000, maxTotalBytes = 3_000_000)
          ),
          RunnerPipelineNameRule(),
          SafetyAnalyzer(),
          { dev.lawlan.runline.analyzer.AllowList("config", allowList) },
          store,
          UploadTelemetry(otel),
          Clock.fixed(now, ZoneOffset.UTC),
      )

  private fun upload(jar: Path, uploader: String = "alice"): UploadResult =
      staging.stage(Files.newInputStream(jar), 10_000_000).use { service.upload(it, uploader) }

  private fun jar(
      vararg sources: Pair<String, String>,
      extra: Map<String, ByteArray> = emptyMap(),
  ): Path = PipelineJars.build(dir, "p-${System.nanoTime()}.jar", sources.toMap(), extra)

  private fun count(table: String): Long =
      dataSource.connection.use { c ->
        c.createStatement().use { s ->
          s.executeQuery("SELECT count(*) FROM $table").use {
            it.next()
            it.getLong(1)
          }
        }
      }

  private fun assertNothingStored() {
    assertEquals(0, count("pipeline_artifact"))
    assertEquals(0, count("pipeline_definition"))
    assertEquals(0, Files.list(staging.directory).use { it.count() }, "staged file left behind")
  }

  private fun rejected(result: UploadResult) = assertIs<UploadResult.Rejected>(result)

  @Test
  fun `stores a safe pipeline with its metadata, uploader and the allow list version`() {
    val path =
        jar(
            "demo.Hello" to
                PipelineJars.pipeline(
                    "demo.Hello",
                    "hello",
                    "network = @AccessLimit(allow = {\"a.com\"}), processes = @AccessLimit(allow = {})",
                )
        )

    val artifact = assertIs<UploadResult.Created>(upload(path, "alice")).artifact

    assertEquals("alice", artifact.uploadedBy)
    assertEquals(now, artifact.uploadedAt)
    assertEquals(Files.size(path), artifact.sizeBytes)
    val d = artifact.definitions.single()
    assertEquals("demo.Hello", d.className)
    assertEquals("hello", d.name)
    assertEquals(Verdict.SAFE, d.verdict)
    assertEquals(emptyList(), d.reasons)
    assertEquals("config", d.allowListVersion)
    assertEquals(listOf("a.com"), d.metadata.network.allow)
    assertFalse(d.allowUnsafeExecution)
    assertEquals(artifact, store.findByHash(artifact.contentHash))
  }

  @Test
  fun `stores the complete metadata of every declaration form, exactly as the API returns it`() {
    val path =
        jar(
            "demo.Nightly" to
                PipelineJars.pipeline(
                    "demo.Nightly",
                    "nightly",
                    definition =
                        """
                        parameters = { @Param(name = "env"), @Param(name = "flag", required = false) },
                        files = {
                          @FileAccess(scope = FileScope.PIPELINE_SHARED),
                          @FileAccess(scope = FileScope.RUN_PRIVATE, mode = FileMode.READ_WRITE)
                        },
                        network = @AccessLimit(allow = {"example.com", "api.example.org"}),
                        processes = @AccessLimit(unrestricted = true),
                        resources = {"db-lock"}
                        """
                            .trimIndent(),
                )
        )

    val d = assertIs<UploadResult.Created>(upload(path)).artifact.definitions.single()

    assertEquals(
        MetadataDoc(
            parameters = listOf(ParameterDoc("env", true, null), ParameterDoc("flag", false, "")),
            files =
                listOf(
                    FileAccessDoc("PIPELINE_SHARED", "READ_ONLY"),
                    FileAccessDoc("RUN_PRIVATE", "READ_WRITE"),
                ),
            network = AccessLimitDoc(false, listOf("example.com", "api.example.org")),
            processes = AccessLimitDoc(true, emptyList()),
            resources = listOf("db-lock"),
        ),
        d.metadata,
    )
  }

  @Test
  fun `stores the declared resource types with the names and a name only declaration stays as before`() {
    val path =
        jar(
            "demo.Typed" to
                PipelineJars.pipeline(
                    "demo.Typed",
                    "typed",
                    definition =
                        "network = @AccessLimit(allow = {}), processes = @AccessLimit(allow = {}), " +
                            "resources = {\"db-lock\"}, typedResources = { " +
                            "@TypedResource(name = \"data\", type = ResourceTypes.FILE), " +
                            "@TypedResource(name = \"odd\", type = \"not-a-type\") }",
                ),
            "demo.Plain" to
                PipelineJars.pipeline(
                    "demo.Plain",
                    "plain",
                    definition =
                        "network = @AccessLimit(allow = {}), processes = @AccessLimit(allow = {}), " +
                            "resources = {\"db-lock\"}",
                ),
        )

    val created = assertIs<UploadResult.Created>(upload(path)).artifact
    val typed = created.definitions.single { it.name == "typed" }
    val plain = created.definitions.single { it.name == "plain" }

    assertEquals(listOf("db-lock", "data", "odd"), typed.metadata.resources)
    assertEquals(mapOf("data" to "file", "odd" to "not-a-type"), typed.metadata.resourceTypes)
    assertEquals(Verdict.SAFE, typed.verdict)
    assertEquals(emptyMap(), plain.metadata.resourceTypes)
    assertEquals(created, store.findByHash(created.contentHash))
  }

  @Test
  fun `stores unrestricted limits and empty lists for members that are not declared`() {
    val path = jar("demo.Bare" to PipelineJars.pipeline("demo.Bare", "bare", definition = ""))

    val d = assertIs<UploadResult.Created>(upload(path)).artifact.definitions.single()

    assertEquals(
        MetadataDoc(
            parameters = emptyList(),
            files = emptyList(),
            network = AccessLimitDoc(true, emptyList()),
            processes = AccessLimitDoc(true, emptyList()),
            resources = emptyList(),
        ),
        d.metadata,
    )
    assertEquals(Verdict.UNSAFE, d.verdict)
  }

  @Test
  fun `stores the metadata of a pipeline compiled from Kotlin with parameter defaults`() {
    val path =
        PipelineJars.jarOf(
            dir,
            "k-${System.nanoTime()}.jar",
            dev.lawlan.runline.engine.fixtures.KotlinFixturePipeline::class.java,
        )

    val d = assertIs<UploadResult.Created>(upload(path)).artifact.definitions.single()

    assertEquals(
        MetadataDoc(
            parameters =
                listOf(ParameterDoc("env", true, null), ParameterDoc("retries", false, "3")),
            files = listOf(FileAccessDoc("RUN_PRIVATE", "READ_WRITE")),
            network = AccessLimitDoc(false, listOf("example.com")),
            processes = AccessLimitDoc(false, emptyList()),
            resources = listOf("db-lock"),
        ),
        d.metadata,
    )
  }

  @Test
  fun `reports unsafe reasons with dependency paths`() {
    val path =
        jar(
            "demo.P" to PipelineJars.pipeline("demo.P", "p", members = "demo.Helper helper;"),
            "demo.Helper" to "package demo; public class Helper { java.io.File f; }",
        )

    val d = assertIs<UploadResult.Created>(upload(path)).artifact.definitions.single()

    assertEquals(Verdict.UNSAFE, d.verdict)
    val reason = d.reasons.single { it.kind == ReasonKind.NOT_ALLOW_LISTED }
    assertEquals("java.io.File", reason.className)
    assertEquals(listOf("demo.P", "demo.Helper", "java.io.File"), reason.path)
  }

  @Test
  fun `a JVM exit reference is unsafe even when java lang is allowed`() {
    val path = jar("demo.P" to PipelineJars.pipeline("demo.P", "p", body = "System.exit(1);"))

    val d = assertIs<UploadResult.Created>(upload(path)).artifact.definitions.single()

    assertEquals(Verdict.UNSAFE, d.verdict)
    assertTrue(d.reasons.any { it.kind == ReasonKind.JVM_EXIT && it.member!!.contains("exit") })
  }

  @Test
  fun `an IO sensitive member is unsafe even when java lang is allowed and is stored as its own reason kind`() {
    val path =
        jar(
            "demo.P" to
                PipelineJars.pipeline(
                    "demo.P",
                    "p",
                    body = "try { new ProcessBuilder(\"ls\").start(); } catch (Exception e) {}",
                )
        )

    val artifact = assertIs<UploadResult.Created>(upload(path)).artifact
    val d = artifact.definitions.single()

    assertEquals(Verdict.UNSAFE, d.verdict)
    val reason = d.reasons.single()
    assertEquals(ReasonKind.IO_SENSITIVE_MEMBER, reason.kind)
    assertEquals("java.lang.ProcessBuilder.start()Ljava/lang/Process;", reason.member)
    assertEquals(listOf("demo.P"), reason.path)
    assertEquals(artifact, store.findByHash(artifact.contentHash))
  }

  @Test
  fun `a class entry in the allow list makes printing safe while opening a file stays unsafe`() {
    val prints = "System.out.println(\"hi\");"
    val opens = "try { new java.io.PrintStream(\"out.txt\"); } catch (Exception e) {}"
    val path =
        jar(
            "demo.Prints" to PipelineJars.pipeline("demo.Prints", "prints", body = prints),
            "demo.Opens" to PipelineJars.pipeline("demo.Opens", "opens", body = opens),
        )

    val withoutEntry =
        assertIs<UploadResult.Created>(upload(path)).artifact.definitions.associateBy { it.name }
    assertEquals(Verdict.UNSAFE, withoutEntry.getValue("prints").verdict)

    allowList = allowList + ClassEntry("java.io.PrintStream")
    val withEntry =
        assertIs<UploadResult.Created>(
                upload(
                    jar(
                        "demo.Prints" to
                            PipelineJars.pipeline("demo.Prints", "prints", body = prints),
                        "demo.Opens" to PipelineJars.pipeline("demo.Opens", "opens", body = opens),
                        extra = mapOf("marker" to byteArrayOf(1)),
                    )
                )
            )
            .artifact
            .definitions
            .associateBy { it.name }

    assertEquals(Verdict.SAFE, withEntry.getValue("prints").verdict)
    assertEquals(Verdict.UNSAFE, withEntry.getValue("opens").verdict)
    assertEquals(
        listOf(ReasonKind.IO_SENSITIVE_MEMBER),
        withEntry.getValue("opens").reasons.map { it.kind },
    )
  }

  @Test
  fun `unrestricted network is unsafe and an empty allow list judges java types unsafe`() {
    allowList = emptyList()
    val path = jar("demo.P" to PipelineJars.pipeline("demo.P", "p", definition = ""))

    val d = assertIs<UploadResult.Created>(upload(path)).artifact.definitions.single()

    assertEquals(Verdict.UNSAFE, d.verdict)
    assertEquals(
        setOf("NETWORK", "PROCESSES"),
        d.reasons.filter { it.kind == ReasonKind.UNRESTRICTED_ACCESS }.map { it.category }.toSet(),
    )
  }

  @Test
  fun `discovery does not run pipeline code`() {
    val path =
        jar(
            "demo.P" to
                PipelineJars.pipeline(
                    "demo.P",
                    "p",
                    members = "static { if (true) throw new IllegalStateException(\"ran\"); }",
                )
        )

    assertIs<UploadResult.Created>(upload(path))
  }

  @Test
  fun `the same content uploaded again is the same version and nothing new is written`() {
    val path = jar("demo.P" to PipelineJars.pipeline("demo.P", "p"))
    val first = assertIs<UploadResult.Created>(upload(path, "alice")).artifact

    val second = assertIs<UploadResult.Existing>(upload(path, "bob")).artifact

    assertEquals(first, second)
    assertEquals(1, count("pipeline_artifact"))
    assertEquals(1, count("pipeline_definition"))
  }

  @Test
  fun `a new version of the same pipeline name is a separate record`() {
    val v1 = jar("demo.P" to PipelineJars.pipeline("demo.P", "p"))
    val v2 = jar("demo.P" to PipelineJars.pipeline("demo.P", "p", body = "int x = 1;"))

    val a = assertIs<UploadResult.Created>(upload(v1)).artifact
    val b = assertIs<UploadResult.Created>(upload(v2)).artifact

    assertNotEquals(a.contentHash, b.contentHash)
    assertEquals("p", b.definitions.single().name)
    assertFalse(b.definitions.single().allowUnsafeExecution)
  }

  @Test
  fun `a file that is not a jar is rejected and nothing is stored`() {
    val notJar = Files.write(dir.resolve("x.jar"), "plain text".toByteArray())

    val r = rejected(upload(notJar))

    assertEquals(RejectionReason.NOT_A_JAR, r.reason)
    assertNothingStored()
  }

  @Test
  fun `a jar without any pipeline is rejected`() {
    val r = rejected(upload(jar("demo.Plain" to "package demo; public class Plain {}")))

    assertEquals(RejectionReason.NO_PIPELINE_FOUND, r.reason)
    assertNothingStored()
  }

  @Test
  fun `a jar whose only class is unreadable is rejected naming the class`() {
    val broken =
        jar(
            "demo.Ok" to "package demo; public class Ok {}",
            extra = mapOf("demo/Broken.class" to "junk".toByteArray()),
        )

    val r = rejected(upload(broken))

    assertEquals(RejectionReason.METADATA_UNREADABLE, r.reason)
    assertTrue(r.message.contains("demo.Broken"), r.message)
    assertNothingStored()
  }

  @Test
  fun `two pipelines with the same name in one jar are rejected`() {
    val path =
        jar(
            "demo.A" to PipelineJars.pipeline("demo.A", "same"),
            "demo.B" to PipelineJars.pipeline("demo.B", "same"),
        )

    val r = rejected(upload(path))

    assertEquals(RejectionReason.DUPLICATE_PIPELINE_NAME, r.reason)
    assertTrue(r.message.contains("same"))
    assertNothingStored()
  }

  @Test
  fun `a jar bundling core classes is rejected with the reason and the remedy`() {
    val path =
        jar(
            "demo.P" to PipelineJars.pipeline("demo.P", "p"),
            extra =
                mapOf(
                    "dev/lawlan/runline/core/Pipeline.class" to
                        PipelineJars.coreClassBytes("Pipeline")
                ),
        )

    val r = rejected(upload(path))

    assertEquals(RejectionReason.CORE_CLASSES_BUNDLED, r.reason)
    assertTrue(r.message.contains("dev.lawlan.runline.core"), r.message)
    assertTrue(r.message.contains("Runner"), "states why: run uses the Runner's core: ${r.message}")
    assertTrue(r.message.contains("不夾帶"), "states what the author should do: ${r.message}")
    assertTrue(r.message.contains("dev.lawlan.runline.core.Pipeline"), "names an offending class")
    assertNothingStored()
  }

  // ---- expansion limits (WI-18): the guard runs before the analyzer reads anything ----

  @Test
  fun `a compressed entry that expands past the entry limit is rejected, naming the limit`() {
    val bomb =
        jar(
            "demo.P" to PipelineJars.pipeline("demo.P", "p"),
            extra = zeros("data/z.bin", 5_000_000),
        )
    assertTrue(Files.size(bomb) < 100_000, "small on disk")

    val r = rejected(upload(bomb))

    assertEquals(RejectionReason.JAR_ENTRY_TOO_LARGE, r.reason)
    assertTrue(r.message.contains("data/z.bin"), r.message)
    assertTrue(r.message.contains("1000000"), "states the limit: ${r.message}")
    assertNothingStored()
  }

  @Test
  fun `entries that together expand past the total limit are rejected, naming the limit`() {
    val parts = (1..4).associate { "data/part$it.bin" to ByteArray(900_000) }

    val r = rejected(upload(jar("demo.P" to PipelineJars.pipeline("demo.P", "p"), extra = parts)))

    assertEquals(RejectionReason.JAR_EXPANDED_TOO_LARGE, r.reason)
    assertTrue(r.message.contains("3000000"), "states the limit: ${r.message}")
    assertNothingStored()
  }

  @Test
  fun `a jar with too many entries is rejected, naming the limit`() {
    val many = (1..150).associate { "data/f$it.txt" to "x".toByteArray() }

    val r = rejected(upload(jar("demo.P" to PipelineJars.pipeline("demo.P", "p"), extra = many)))

    assertEquals(RejectionReason.JAR_TOO_MANY_ENTRIES, r.reason)
    assertTrue(r.message.contains("100"), "states the limit: ${r.message}")
    assertNothingStored()
  }

  @Test
  fun `a bomb posing as a class file is refused before the analyzer reads it`() {
    // The analyzer would read this entry whole to parse it and report it unreadable; the guard has
    // to answer first.
    val bomb =
        jar(
            "demo.P" to PipelineJars.pipeline("demo.P", "p"),
            extra = zeros("demo/Huge.class", 5_000_000),
        )

    val r = rejected(upload(bomb))

    assertEquals(RejectionReason.JAR_ENTRY_TOO_LARGE, r.reason)
    assertNothingStored()
  }

  @Test
  fun `a jar that expands to far more than the test JVM could hold is refused before the analyzer reads it`() {
    // Compressed, under a megabyte; expanded, 900 MB, which the analyzer would read whole to parse
    // it as a class (the test JVM's heap is smaller, so that would not survive).
    val small = jar("demo.P" to PipelineJars.pipeline("demo.P", "p"))
    val bomb =
        PipelineJars.withZeroEntry(
            small,
            dir.resolve("huge-${System.nanoTime()}.jar"),
            "demo/Huge.class",
            900L * 1024 * 1024,
        )
    assertTrue(Files.size(bomb) < 5_000_000, "compressed size ${Files.size(bomb)}")

    val r = rejected(upload(bomb))

    assertEquals(RejectionReason.JAR_ENTRY_TOO_LARGE, r.reason)
    assertNothingStored()
  }

  private fun zeros(name: String, size: Int) = mapOf(name to ByteArray(size))

  // ---- pipeline names (WI-18) ----

  @Test
  fun `a pipeline name that cannot be a directory name is rejected naming the pipeline and the allowed characters`() {
    for (name in listOf("has space", "a/b", "..", ".", "über", "semi;colon", "tab\there")) {
      val r = rejected(upload(jar("demo.P" to PipelineJars.pipeline("demo.P", name))))

      assertEquals(RejectionReason.INVALID_PIPELINE_NAME, r.reason, name)
      assertTrue(
          r.message.contains("'$name'") || r.message.contains("「$name」"),
          "names $name: ${r.message}",
      )
      assertTrue(r.message.contains("letters, digits"), "states what is allowed: ${r.message}")
      assertNothingStored()
    }
  }

  @Test
  fun `one invalid name among valid pipelines rejects the whole upload`() {
    val path =
        jar(
            "demo.Good" to PipelineJars.pipeline("demo.Good", "good"),
            "demo.Bad" to PipelineJars.pipeline("demo.Bad", "not ok"),
        )

    val r = rejected(upload(path))

    assertEquals(RejectionReason.INVALID_PIPELINE_NAME, r.reason)
    assertTrue(r.message.contains("not ok"), r.message)
    assertFalse(r.message.contains("'good'"), "only the offender is named: ${r.message}")
    assertNothingStored()
  }

  @Test
  fun `names of letters, digits, dot, underscore and hyphen are accepted`() {
    for (name in listOf("Nightly-Report_v2.1", "a", "...", "-x", "9")) {
      val created =
          assertIs<UploadResult.Created>(
              upload(jar("demo.P" to PipelineJars.pipeline("demo.P", name))),
              name,
          )
      assertEquals(name, created.artifact.definitions.single().name)
    }
  }

  @Test
  fun `rejections and results are counted by result and verdict`() {
    upload(jar("demo.A" to PipelineJars.pipeline("demo.A", "a")))
    val unsafe = jar("demo.B" to PipelineJars.pipeline("demo.B", "b", definition = ""))
    upload(unsafe)
    upload(unsafe)
    upload(Files.write(dir.resolve("x.jar"), "nope".toByteArray()))

    val metrics = metricReader.collectAllMetrics()
    val uploads =
        metrics
            .first { it.name == "runline.upload.count" }
            .longSumData
            .points
            .associate {
              (it.attributes.get(AttributeKey.stringKey("result")) +
                  it.attributes.get(AttributeKey.stringKey("reason")).let { r ->
                    if (r == null) "" else ":$r"
                  }) to it.value
            }
    assertEquals(mapOf("created" to 2L, "existing" to 1L, "rejected:not_a_jar" to 1L), uploads)
    val verdicts =
        metrics
            .first { it.name == "runline.upload.pipelines" }
            .longSumData
            .points
            .associate { it.attributes.get(AttributeKey.stringKey("verdict"))!! to it.value }
    assertEquals(mapOf("SAFE" to 1L, "UNSAFE" to 1L), verdicts)
  }

  @Test
  fun `each upload is traced with the uploader name and the result`() {
    upload(jar("demo.A" to PipelineJars.pipeline("demo.A", "a")), "alice")

    val span = spans.finishedSpanItems.single { it.name == "runline.upload" }
    assertEquals("alice", span.attributes.get(AttributeKey.stringKey("runline.uploader")))
    assertEquals("created", span.attributes.get(AttributeKey.stringKey("runline.upload.result")))
  }
}
