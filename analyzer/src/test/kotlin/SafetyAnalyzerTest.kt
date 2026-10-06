package dev.lawlan.runline.analyzer

import demo.KotlinExitPipeline
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class SafetyAnalyzerTest {
  @TempDir lateinit var tmp: Path

  private val javaLang = AllowListEntry("java.lang")

  private fun allowList(vararg entries: AllowListEntry, version: String = "v1") =
      AllowList(version, entries.toList())

  private fun analyze(
      sources: Map<String, String>,
      allowList: AllowList = allowList(javaLang),
      classOverrides: Map<String, (ByteArray) -> ByteArray> = emptyMap(),
      analyzer: SafetyAnalyzer = SafetyAnalyzer(),
  ): SafetyReport =
      analyzer.analyze(CompiledJars.build(tmp, "p.jar", sources, classOverrides), allowList)

  @Test
  fun `pipeline limited to core and allowed packages is safe and records the allow list version`() {
    val report =
        analyze(
            mapOf("demo.Hello" to CompiledJars.pipeline("demo.Hello")),
            allowList(javaLang, version = "v7"),
        )

    val pipeline = report.pipelines.single()
    assertEquals("demo.Hello", pipeline.className)
    assertEquals("Hello-pipeline", pipeline.pipelineName)
    assertEquals(emptyList(), pipeline.reasons)
    assertEquals(Verdict.SAFE, pipeline.verdict)
    assertEquals("v7", report.allowListVersion)
  }

  private fun reasonsOf(report: SafetyReport) = report.pipelines.single().reasons

  @Test
  fun `network and process limits that are omitted or declared unrestricted make the pipeline unsafe`() {
    val omitted = analyze(mapOf("demo.A" to CompiledJars.pipeline("demo.A", limits = "")))
    assertEquals(
        listOf(
            UnsafeReason.UnrestrictedAccess(IoCategory.NETWORK),
            UnsafeReason.UnrestrictedAccess(IoCategory.PROCESSES),
        ),
        reasonsOf(omitted),
    )

    val explicit =
        analyze(
            mapOf(
                "demo.B" to
                    CompiledJars.pipeline(
                        "demo.B",
                        limits =
                            "network = @AccessLimit(allow = {\"example.com\"}), " +
                                "processes = @AccessLimit(unrestricted = true)",
                    )
            )
        )
    assertEquals(
        listOf(UnsafeReason.UnrestrictedAccess(IoCategory.PROCESSES)),
        reasonsOf(explicit),
    )
    assertEquals(Verdict.UNSAFE, explicit.pipelines.single().verdict)
  }

  private fun helper(fqcn: String, body: String) =
      """
      package ${fqcn.substringBeforeLast('.')};
      public class ${fqcn.substringAfterLast('.')} {
        public static void work() { $body }
      }
      """
          .trimIndent()

  @Test
  fun `a class outside the allow list is a violation reported with its dependency path`() {
    val report =
        analyze(
            mapOf(
                "demo.Hello" to
                    CompiledJars.pipeline("demo.Hello", body = "com.lib.Helper.work();"),
                "com.lib.Helper" to helper("com.lib.Helper", "new java.io.File(\"x\");"),
            )
        )

    assertEquals(
        listOf(
            UnsafeReason.NotAllowListed("com.lib.Helper", listOf("demo.Hello", "com.lib.Helper")),
            UnsafeReason.NotAllowListed(
                "java.io.File",
                listOf("demo.Hello", "com.lib.Helper", "java.io.File"),
            ),
        ),
        reasonsOf(report),
    )
  }

  @Test
  fun `an allow listed class is trusted so its own references are not inspected`() {
    val report =
        analyze(
            mapOf(
                "demo.Hello" to
                    CompiledJars.pipeline("demo.Hello", body = "com.lib.Helper.work();"),
                "com.lib.Helper" to helper("com.lib.Helper", "new java.io.File(\"x\");"),
            ),
            allowList(javaLang, AllowListEntry("com.lib")),
        )

    assertEquals(emptyList(), reasonsOf(report))
  }

  private val usesSubPackage =
      mapOf(
          "demo.Hello" to CompiledJars.pipeline("demo.Hello", body = "com.lib.sub.Deep.work();"),
          "com.lib.sub.Deep" to helper("com.lib.sub.Deep", ""),
      )

  @Test
  fun `an allow list entry covers its sub-packages by default`() {
    val report = analyze(usesSubPackage, allowList(javaLang, AllowListEntry("com.lib")))

    assertEquals(emptyList(), reasonsOf(report))
  }

  @Test
  fun `an exact-only allow list entry does not cover sub-packages`() {
    val report =
        analyze(usesSubPackage, allowList(javaLang, AllowListEntry("com.lib", exactOnly = true)))

    assertEquals(
        listOf(
            UnsafeReason.NotAllowListed(
                "com.lib.sub.Deep",
                listOf("demo.Hello", "com.lib.sub.Deep"),
            )
        ),
        reasonsOf(report),
    )
  }

  @Test
  fun `JVM exit members are unsafe even though their package is on the allow list`() {
    val report =
        analyze(
            mapOf(
                "demo.Hello" to
                    CompiledJars.pipeline(
                        "demo.Hello",
                        body =
                            "System.exit(1); Runtime.getRuntime().exit(2); Runtime.getRuntime().halt(3);",
                    )
            ),
            allowList(javaLang),
        )

    assertEquals(
        listOf(
            UnsafeReason.JvmExit("java.lang.Runtime.exit", listOf("demo.Hello")),
            UnsafeReason.JvmExit("java.lang.Runtime.halt", listOf("demo.Hello")),
            UnsafeReason.JvmExit("java.lang.System.exit", listOf("demo.Hello")),
        ),
        reasonsOf(report),
    )
  }

  private val exitInLibrary =
      mapOf(
          "demo.Hello" to CompiledJars.pipeline("demo.Hello", body = "com.lib.Helper.work();"),
          "com.lib.Helper" to helper("com.lib.Helper", "System.exit(1);"),
      )

  @Test
  fun `a JVM exit inside a bundled third-party class is reported with the path to that class`() {
    val report = analyze(exitInLibrary, allowList(javaLang))

    assertTrue(
        UnsafeReason.JvmExit("java.lang.System.exit", listOf("demo.Hello", "com.lib.Helper")) in
            reasonsOf(report)
    )
  }

  @Test
  fun `a JVM exit inside an allow listed library is trusted and not inspected`() {
    val report = analyze(exitInLibrary, allowList(javaLang, AllowListEntry("com.lib")))

    assertEquals(emptyList(), reasonsOf(report))
  }

  private val kotlinPkg = AllowListEntry("kotlin")

  // javac cannot see the real kotlin.system.ProcessKt.exitProcess (inline-only, private in
  // bytecode), so this input declares a public one with the same owner and name to compile a
  // direct member reference. The class is allow listed (kotlin), hence trusted and never expanded.
  private val kotlinProcessSource =
      """
      package kotlin.system;
      public final class ProcessKt {
        public static Void exitProcess(int status) { return null; }
      }
      """
          .trimIndent()

  @Test
  fun `a kotlin system exit function is a JVM exit even though kotlin is on the allow list`() {
    val report =
        analyze(
            mapOf(
                "demo.Hello" to
                    CompiledJars.pipeline(
                        "demo.Hello",
                        body = "kotlin.system.ProcessKt.exitProcess(1);",
                    ),
                "kotlin.system.ProcessKt" to kotlinProcessSource,
            ),
            allowList(javaLang, kotlinPkg),
        )

    assertEquals(
        listOf(UnsafeReason.JvmExit("kotlin.system.ProcessKt.exitProcess", listOf("demo.Hello"))),
        reasonsOf(report),
    )
  }

  @Test
  fun `a kotlin system exit function inside a bundled library is reported with the path to it`() {
    val report =
        analyze(
            mapOf(
                "demo.Hello" to
                    CompiledJars.pipeline("demo.Hello", body = "com.lib.Helper.work();"),
                "com.lib.Helper" to
                    helper("com.lib.Helper", "kotlin.system.ProcessKt.exitProcess(1);"),
                "kotlin.system.ProcessKt" to kotlinProcessSource,
            ),
            allowList(javaLang, kotlinPkg),
        )

    assertTrue(
        UnsafeReason.JvmExit(
            "kotlin.system.ProcessKt.exitProcess",
            listOf("demo.Hello", "com.lib.Helper"),
        ) in reasonsOf(report)
    )
  }

  @Test
  fun `exitProcess written in Kotlin is a JVM exit`() {
    val report =
        SafetyAnalyzer()
            .analyze(
                KotlinFixtures.jarOf(tmp, "k.jar", KotlinExitPipeline::class.java),
                allowList(javaLang, kotlinPkg),
            )

    assertTrue(reportHasJvmExit(report, "demo.KotlinExitPipeline"))
  }

  private fun reportHasJvmExit(report: SafetyReport, pipeline: String) =
      report.pipelines
          .single { it.className == pipeline }
          .reasons
          .any { it is UnsafeReason.JvmExit && it.path == listOf(pipeline) }

  @Test
  fun `core is a trusted leaf and bundled core classes are not analyzed`() {
    val report =
        analyze(
            mapOf(
                "demo.Hello" to
                    CompiledJars.pipeline(
                        "demo.Hello",
                        body = "dev.lawlan.runline.core.Bundled.work();",
                    ),
                "dev.lawlan.runline.core.Bundled" to
                    helper(
                        "dev.lawlan.runline.core.Bundled",
                        "new java.io.File(\"x\"); System.exit(1);",
                    ),
            ),
            allowList(javaLang),
        )

    assertEquals(emptyList(), reasonsOf(report))
  }

  private val javaUtil = AllowListEntry("java.util")

  @Test
  fun `types that appear only in declarations or by full name are still references`() {
    val report =
        analyze(
            mapOf(
                "demo.Hello" to
                    CompiledJars.pipeline(
                        "demo.Hello",
                        members =
                            "private java.io.File file; " +
                                "private java.util.List<java.net.URI> uris; " +
                                "private java.nio.file.Path[] paths;",
                    )
            ),
            allowList(javaLang, javaUtil),
        )

    assertEquals(
        setOf("java.io.File", "java.net.URI", "java.nio.file.Path"),
        reasonsOf(report).map { (it as UnsafeReason.NotAllowListed).className }.toSet(),
    )
    reasonsOf(report).forEach { assertEquals(2, (it as UnsafeReason.NotAllowListed).path.size) }
  }

  @Test
  fun `a class referenced only through an annotation is a reference`() {
    val report =
        analyze(
            mapOf(
                "demo.Hello" to
                    CompiledJars.pipeline("demo.Hello", members = "@com.lib.Marker void m() {}"),
                "com.lib.Marker" to
                    "package com.lib; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface Marker {}",
            ),
            allowList(javaLang, AllowListEntry("java.lang.annotation")),
        )

    assertEquals(
        listOf(
            UnsafeReason.NotAllowListed("com.lib.Marker", listOf("demo.Hello", "com.lib.Marker"))
        ),
        reasonsOf(report),
    )
  }

  @Test
  fun `a method handle to a JVM exit member is a JVM exit reference`() {
    val report =
        analyze(
            mapOf(
                "demo.Hello" to
                    CompiledJars.pipeline(
                        "demo.Hello",
                        body = "java.util.function.IntConsumer c = System::exit;",
                    )
            ),
            allowList(javaLang, AllowListEntry("java.util.function")),
        )

    assertEquals(
        listOf(UnsafeReason.JvmExit("java.lang.System.exit", listOf("demo.Hello"))),
        reasonsOf(report),
    )
  }

  @Test
  fun `cyclic references terminate and each node is reported once`() {
    val report =
        analyze(
            mapOf(
                "demo.Hello" to CompiledJars.pipeline("demo.Hello", body = "com.lib.A.work();"),
                "com.lib.A" to helper("com.lib.A", "com.lib.B.work();"),
                "com.lib.B" to helper("com.lib.B", "com.lib.A.work();"),
            )
        )

    assertEquals(
        listOf(
            UnsafeReason.NotAllowListed("com.lib.A", listOf("demo.Hello", "com.lib.A")),
            UnsafeReason.NotAllowListed(
                "com.lib.B",
                listOf("demo.Hello", "com.lib.A", "com.lib.B"),
            ),
        ),
        reasonsOf(report),
    )
  }

  @Test
  fun `a node reachable by several paths is reported at least once`() {
    val report =
        analyze(
            mapOf(
                "demo.Hello" to
                    CompiledJars.pipeline(
                        "demo.Hello",
                        body = "com.lib.A.work(); com.lib.B.work();",
                    ),
                "com.lib.A" to helper("com.lib.A", "com.lib.C.work();"),
                "com.lib.B" to helper("com.lib.B", "com.lib.C.work();"),
                "com.lib.C" to helper("com.lib.C", ""),
            ),
            allowList(javaLang),
        )

    val toC =
        reasonsOf(report).filterIsInstance<UnsafeReason.NotAllowListed>().filter {
          it.className == "com.lib.C"
        }
    assertTrue(toC.isNotEmpty())
    assertEquals("demo.Hello", toC.first().path.first())
    assertEquals("com.lib.C", toC.first().path.last())
  }

  @Test
  fun `every pipeline in a jar gets its own verdict`() {
    val report =
        analyze(
            mapOf(
                "demo.Clean" to CompiledJars.pipeline("demo.Clean"),
                "demo.Dirty" to CompiledJars.pipeline("demo.Dirty", body = "System.exit(0);"),
            )
        )

    assertEquals(
        mapOf("demo.Clean" to Verdict.SAFE, "demo.Dirty" to Verdict.UNSAFE),
        report.pipelines.associate { it.className to it.verdict },
    )
  }

  private val usesLibrary =
      mapOf(
          "demo.Hello" to CompiledJars.pipeline("demo.Hello", body = "com.lib.Helper.work();"),
          "com.lib.Helper" to helper("com.lib.Helper", ""),
      )

  @Test
  fun `a class file that cannot be parsed makes the pipeline unsafe with the reason`() {
    val report =
        analyze(
            usesLibrary,
            allowList(javaLang),
            classOverrides = mapOf("com.lib.Helper" to { _ -> "not a class file".toByteArray() }),
        )

    val unreadable = reasonsOf(report).filterIsInstance<UnsafeReason.UnreadableClass>().single()
    assertEquals("com.lib.Helper", unreadable.className)
    assertEquals(listOf("demo.Hello", "com.lib.Helper"), unreadable.path)
    assertTrue(unreadable.detail.isNotBlank())
    assertEquals(Verdict.UNSAFE, report.pipelines.single().verdict)
  }

  @Test
  fun `a class file newer than the JDK 25 baseline is unreadable and therefore unsafe`() {
    val report =
        analyze(
            usesLibrary,
            allowList(javaLang),
            classOverrides =
                mapOf(
                    "com.lib.Helper" to
                        { bytes ->
                          bytes.copyOf().also {
                            it[6] = 0
                            it[7] = 99
                          } // major version 99
                        }
                ),
        )

    assertTrue(reasonsOf(report).any { it is UnsafeReason.UnreadableClass })
  }

  @Test
  fun `class files produced by the JDK 25 compiler are readable`() {
    val jar =
        CompiledJars.build(tmp, "v.jar", mapOf("demo.Hello" to CompiledJars.pipeline("demo.Hello")))
    java.util.zip.ZipFile(jar.toFile()).use { zip ->
      val bytes = zip.getInputStream(zip.getEntry("demo/Hello.class")).readAllBytes()
      assertEquals(69, ((bytes[6].toInt() and 0xff) shl 8) or (bytes[7].toInt() and 0xff))
    }

    val report = SafetyAnalyzer().analyze(jar, allowList(javaLang))

    assertEquals(Verdict.SAFE, report.pipelines.single().verdict)
  }

  private val ownHelper =
      mapOf(
          "demo.Hello" to CompiledJars.pipeline("demo.Hello", body = "demo.A.work();"),
          "demo.A" to helper("demo.A", ""),
      )

  @Test
  fun `exceeding the class budget makes the pipeline unsafe and says why`() {
    val within = analyze(ownHelper, analyzer = SafetyAnalyzer(AnalysisLimits(maxClasses = 2)))
    assertEquals(emptyList(), reasonsOf(within))

    val exceeded = analyze(ownHelper, analyzer = SafetyAnalyzer(AnalysisLimits(maxClasses = 1)))
    val reason = reasonsOf(exceeded).single() as UnsafeReason.LimitExceeded
    assertTrue(reason.detail.contains("1"))
  }

  @Test
  fun `exceeding the time budget makes the pipeline unsafe and says why`() {
    val report =
        analyze(
            ownHelper,
            analyzer = SafetyAnalyzer(AnalysisLimits(maxDuration = java.time.Duration.ZERO)),
        )

    assertTrue(reasonsOf(report).single() is UnsafeReason.LimitExceeded)
  }

  @Test
  fun `analysis does not run static initializers of pipeline or library classes`() {
    val property = "runline.analysis.touched"
    System.clearProperty(property)
    val sources =
        mapOf(
            "demo.Hello" to
                CompiledJars.pipeline(
                    "demo.Hello",
                    members = "static { System.setProperty(\"$property\", \"pipeline\"); }",
                    body = "com.lib.Helper.work();",
                ),
            "com.lib.Helper" to
                """
                package com.lib;
                public class Helper {
                  static { System.setProperty("$property", "library"); }
                  public static void work() {}
                }
                """
                    .trimIndent(),
        )

    analyze(sources)

    assertEquals(null, System.getProperty(property))
  }

  @Test
  fun `the same input gives the same report`() {
    val sources =
        mapOf(
            "demo.Hello" to
                CompiledJars.pipeline(
                    "demo.Hello",
                    limits = "",
                    body = "com.lib.A.work(); com.lib.B.work(); System.exit(0);",
                ),
            "com.lib.A" to helper("com.lib.A", "new java.io.File(\"x\");"),
            "com.lib.B" to helper("com.lib.B", "java.net.URI.create(\"x\");"),
        )
    val jar = CompiledJars.build(tmp, "r.jar", sources)

    val first = SafetyAnalyzer().analyze(jar, allowList(javaLang))
    val second = SafetyAnalyzer().analyze(jar, allowList(javaLang))

    assertEquals(first, second)
  }

  @Test
  fun `the report states in Traditional Chinese the three limits of the analysis`() {
    val report = analyze(mapOf("demo.Hello" to CompiledJars.pipeline("demo.Hello")))

    assertTrue(report.limitations.contains("不涵蓋反射與動態載入"))
    assertTrue(report.limitations.contains("同樣適用於 JVM 結束呼叫的判定"))
    assertTrue(report.limitations.contains("白名單內的類別不被檢查"))
  }

  @Test
  fun `the pipeline's own classes are allowed but their references are still checked`() {
    val report =
        analyze(
            mapOf(
                "demo.Hello" to CompiledJars.pipeline("demo.Hello", body = "demo.util.A.work();"),
                "demo.util.A" to helper("demo.util.A", "new java.io.File(\"x\");"),
            )
        )

    assertEquals(
        listOf(
            UnsafeReason.NotAllowListed(
                "java.io.File",
                listOf("demo.Hello", "demo.util.A", "java.io.File"),
            )
        ),
        reasonsOf(report),
    )
  }
}
