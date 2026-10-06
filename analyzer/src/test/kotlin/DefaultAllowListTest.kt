package dev.lawlan.runline.analyzer

import demo.defaults.DefaultsFileJdkPipeline
import demo.defaults.DefaultsFileKotlinPipeline
import demo.defaults.DefaultsFilePrintStreamPipeline
import demo.defaults.DefaultsJdkInputPipeline
import demo.defaults.DefaultsKotlinPackagesPipeline
import demo.defaults.DefaultsNetworkPipeline
import demo.defaults.DefaultsProcessPipeline
import demo.defaults.DefaultsStdioPipeline
import demo.defaults.DefaultsTypicalPipeline
import demo.defaults.DefaultsUseFilePipeline
import demo.defaults.DefaultsUseResourcePipeline
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WI-10: the default allow list, checked on pipelines that were really compiled: typical Kotlin
 * pipelines are safe, direct use of files, network or processes is unsafe, and every entry is
 * needed by at least one sample (removing it makes that sample unsafe). The expected content is the
 * table in WI-10 / ADR-013 / ADR-014.
 */
class DefaultAllowListTest {
  private val analyzer = SafetyAnalyzer()
  private val list = DefaultAllowList.allowList()

  // ---- the list itself ----

  @Test
  fun `the single source carries a version`() {
    assertTrue(DefaultAllowList.VERSION.isNotBlank())
    assertEquals("default-2", DefaultAllowList.VERSION)
    assertEquals(DefaultAllowList.VERSION, list.version)
    assertEquals(DefaultAllowList.entries, list.entries)
  }

  @Test
  fun `java lang, java util and kotlin are trusted for their own package only`() {
    val exact = list.entries.filterIsInstance<PackageEntry>().filter { it.exactOnly }
    assertEquals(setOf("java.lang", "java.util", "kotlin"), exact.map { it.packageName }.toSet())
  }

  @Test
  fun `only the standard output, console and resource closing classes are class entries`() {
    assertEquals(
        listOf(
            ClassEntry("java.io.PrintStream"),
            ClassEntry("kotlin.io.ConsoleKt"),
            ClassEntry("kotlin.io.CloseableKt"),
            ClassEntry("java.io.Closeable"),
        ),
        list.entries.filterIsInstance<ClassEntry>(),
    )
  }

  @Test
  fun `the list has the 44 entries of the table, without duplicates`() {
    assertEquals(44, list.entries.size)
    assertEquals(list.entries.size, list.entries.toSet().size)
  }

  @Test
  fun `no entry lets a whole IO package through`() {
    val packages = list.entries.filterIsInstance<PackageEntry>().map { it.packageName }
    for (io in IO_PACKAGES) {
      assertTrue(packages.none { io == it || io.startsWith("$it.") && !exactOf(it) }, io)
    }
  }

  private fun exactOf(pkg: String) =
      list.entries.filterIsInstance<PackageEntry>().single { it.packageName == pkg }.exactOnly

  // ---- verdicts on real samples ----

  @Test
  fun `a typical Kotlin pipeline that prints and reads standard input is safe`() {
    assertEquals(emptyList(), reasonsOf(DefaultsTypicalPipeline::class.java, list))
    assertEquals(emptyList(), reasonsOf(DefaultsStdioPipeline::class.java, list))
    assertEquals(emptyList(), reasonsOf(DefaultsKotlinPackagesPipeline::class.java, list))
  }

  @Test
  fun `closing a resource that is not a file with use is safe, in Kotlin and in Java`() {
    assertEquals(emptyList(), reasonsOf(DefaultsUseResourcePipeline::class.java, list))
    assertEquals(emptyList(), analyzer.analyze(javaCloseableJar(), list).pipelines.single().reasons)
  }

  @Test
  fun `opening a file and then closing it with use is still unsafe`() {
    val reasons = reasonsOf(DefaultsUseFilePipeline::class.java, list)

    assertTrue(
        violations(reasons).containsAll(listOf("java.io.File", "java.io.FileInputStream")),
        "$reasons",
    )
  }

  @Test
  fun `without the resource closing helper class, use is unsafe`() {
    val without = AllowList("without", list.entries - ClassEntry("kotlin.io.CloseableKt"))

    assertEquals(
        listOf("kotlin.io.CloseableKt"),
        violations(reasonsOf(DefaultsUseResourcePipeline::class.java, without)),
    )
  }

  @Test
  fun `without the resource closing interface, use and try-with-resources are unsafe`() {
    val without = AllowList("without", list.entries - ClassEntry("java.io.Closeable"))

    assertTrue(
        "java.io.Closeable" in
            violations(reasonsOf(DefaultsUseResourcePipeline::class.java, without))
    )
    assertEquals(
        listOf("java.io.Closeable"),
        violations(analyzer.analyze(javaCloseableJar(), without).pipelines.single().reasons),
    )
  }

  @Test
  fun `using files directly is unsafe`() {
    val kotlinFiles = reasonsOf(DefaultsFileKotlinPipeline::class.java, list)
    assertTrue(violations(kotlinFiles).containsAll(listOf("java.io.File", "kotlin.io.FilesKt")))
    val jdkFiles = reasonsOf(DefaultsFileJdkPipeline::class.java, list)
    assertTrue(
        violations(jdkFiles).containsAll(listOf("java.nio.file.Files", "java.nio.file.Paths"))
    )
    val printStream = reasonsOf(DefaultsFilePrintStreamPipeline::class.java, list)
    assertEquals(
        listOf("java.io.PrintStream.<init>(Ljava/lang/String;)V"),
        printStream.filterIsInstance<UnsafeReason.IoSensitiveMember>().map { it.member },
    )
  }

  @Test
  fun `using the network directly is unsafe`() {
    val reasons = reasonsOf(DefaultsNetworkPipeline::class.java, list)
    assertTrue(
        violations(reasons).containsAll(listOf("java.net.URL", "java.net.Socket")),
        "$reasons",
    )
  }

  @Test
  fun `starting a process is unsafe although java lang is trusted`() {
    val members =
        reasonsOf(DefaultsProcessPipeline::class.java, list)
            .filterIsInstance<UnsafeReason.IoSensitiveMember>()
            .map { it.member }
    assertTrue(members.any { it.startsWith("java.lang.ProcessBuilder.start") }, "$members")
    assertTrue(members.any { it.startsWith("java.lang.Runtime.exec") }, "$members")
  }

  @Test
  fun `reading standard input straight from the JDK is unsafe`() {
    val reasons = reasonsOf(DefaultsJdkInputPipeline::class.java, list)
    assertTrue("java.io.InputStream" in violations(reasons), "$reasons")
  }

  @Test
  fun `the excluded subpackages and the IO packages are not trusted`() {
    for (excluded in EXCLUDED_CLASSES) {
      val jar = javaProbe(excluded)
      val reasons = analyzer.analyze(jar, list).pipelines.single().reasons
      assertTrue(excluded in violations(reasons), "$excluded was trusted: $reasons")
    }
  }

  // ---- every entry is needed ----

  @Test
  fun `removing any entry makes some sample unsafe`() {
    assertTrue(list.entries.isNotEmpty())
    val samples = safeSamples()
    val unneeded =
        list.entries.filter { entry ->
          val without = AllowList("without", list.entries - entry)
          samples.none { jar ->
            analyzer.analyze(jar, without).pipelines.single().reasons.isNotEmpty()
          }
        }
    assertEquals(emptyList(), unneeded, "no sample needs these entries")
  }

  @Test
  fun `the samples the entries are checked against are safe with the full list`() {
    val samples = safeSamples()
    assertEquals(JAVA_SAMPLES.size + 4, samples.size)
    for (jar in samples) {
      assertEquals(emptyList(), analyzer.analyze(jar, list).pipelines.single().reasons, "$jar")
    }
  }

  // ---- sample building ----

  private fun reasonsOf(sample: Class<*>, allowList: AllowList): List<UnsafeReason> =
      analyzer
          .analyze(KotlinFixtures.jarOf(workDir, "k.jar", sample), allowList)
          .pipelines
          .single()
          .reasons

  private fun violations(reasons: List<UnsafeReason>) =
      reasons.filterIsInstance<UnsafeReason.NotAllowListed>().map { it.className }

  private fun javaProbe(className: String): Path =
      CompiledJars.build(
          workDir,
          "probe-${className.hashCode()}.jar",
          mapOf(
              "probe.P" to CompiledJars.pipeline("probe.P", body = "Object o = $className.class;")
          ),
      )

  private fun javaCloseableJar(): Path =
      SAFE_SAMPLES.value.first { it.fileName.toString() == "java-closeable.jar" }

  private fun safeSamples(): List<Path> = SAFE_SAMPLES.value

  private companion object {
    val workDir: Path = Files.createTempDirectory("default-allow-list")

    val IO_PACKAGES =
        listOf(
            "java.io",
            "java.nio.file",
            "java.nio.channels",
            "java.net",
            "java.sql",
            "java.security",
        )

    /** A class from every package the table keeps out, and the IO packages it does not list. */
    val EXCLUDED_CLASSES =
        listOf(
            "java.lang.foreign.Arena",
            "java.lang.reflect.Method",
            "java.lang.module.ModuleDescriptor",
            "java.lang.classfile.ClassFile",
            "java.lang.management.ManagementFactory",
            "java.lang.instrument.Instrumentation",
            "java.util.zip.ZipFile",
            "java.util.jar.JarFile",
            "java.util.logging.Logger",
            "java.util.prefs.Preferences",
            "java.util.spi.ToolProvider",
            "java.io.File",
            "java.io.InputStream",
            "java.io.Reader",
            "java.io.BufferedReader",
            "java.nio.file.Files",
            "java.nio.channels.FileChannel",
            "java.net.Socket",
            "java.sql.Connection",
            "java.security.MessageDigest",
            "kotlin.io.FilesKt",
            "kotlin.io.path.PathsKt",
        )

    /** Java statements that use one package each; the typical Kotlin samples come on top. */
    val JAVA_SAMPLES: Map<String, Pair<String, String>> =
        mapOf(
            "lambda" to ("" to "Runnable r = () -> {}; r.run(); String s = \"a\" + r;"),
            "annotation" to ("@interface Mark {}" to ""),
            "ref" to ("" to "new java.lang.ref.WeakReference<Object>(new Object());"),
            "record" to ("record R(int a) {}" to "Object o = new R(1); o.toString();"),
            "pattern" to
                ("" to
                    "Object o = 1; String s = switch (o) { case Integer i -> \"i\"; default -> \"x\"; };"),
            "constant" to ("" to "Class<?> c = java.lang.constant.ClassDesc.class;"),
            "kotlin-inline-only" to
                ("" to
                    "Object[] c = { kotlin.system.TimingKt.class, " +
                        "kotlin.experimental.BitwiseOperationsKt.class, " +
                        "kotlin.contracts.ContractBuilderKt.class, kotlin.jdk7.AutoCloseableKt.class };"),
            "closeable" to
                ("static class R implements java.io.Closeable { public void close() {} }" to
                    "try (R r = new R()) { r.toString(); }"),
            "util" to ("" to "new java.util.ArrayList<String>().add(\"a\");"),
            "concurrent" to
                ("" to
                    "new java.util.concurrent.ConcurrentHashMap<String, String>();" +
                        "new java.util.concurrent.atomic.AtomicInteger().incrementAndGet();"),
            "function" to
                ("" to "java.util.function.Function<String, String> f = x -> x; f.apply(\"a\");"),
            "regex" to ("" to "java.util.regex.Pattern.compile(\"a+\").matcher(\"aa\").matches();"),
            "stream" to ("" to "java.util.stream.Stream.of(1, 2).count();"),
            "random" to ("" to "java.util.random.RandomGenerator.getDefault().nextInt();"),
            "time" to ("" to "java.time.Instant.now(); java.time.Duration.ofSeconds(1);"),
            "math" to ("" to "java.math.BigDecimal.ONE.add(java.math.BigDecimal.TEN);"),
            "text" to ("" to "new java.text.DecimalFormat(\"0.0\").format(1.5);"),
            "charset" to ("" to "\"a\".getBytes(java.nio.charset.StandardCharsets.UTF_8);"),
        )

    val SAFE_SAMPLES: Lazy<List<Path>> = lazy {
      val java = JAVA_SAMPLES.map { (name, code) ->
        val (members, body) = code
        CompiledJars.build(
            workDir,
            "java-$name.jar",
            mapOf("sample.J" to CompiledJars.pipeline("sample.J", members = members, body = body)),
        )
      }
      val kotlin =
          listOf(
                  DefaultsTypicalPipeline::class.java,
                  DefaultsStdioPipeline::class.java,
                  DefaultsKotlinPackagesPipeline::class.java,
                  DefaultsUseResourcePipeline::class.java,
              )
              .map { KotlinFixtures.jarOf(workDir, "k-${it.simpleName}.jar", it) }
      java + kotlin
    }
  }
}
