package dev.lawlan.runline.analyzer

import demo.stdio.StdioFileClassesPipeline
import demo.stdio.StdioJdkInputPipeline
import demo.stdio.StdioOpensFilePipeline
import demo.stdio.StdioPrintPipeline
import demo.stdio.StdioReadLinePipeline
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * WI-24 / ADR-014 on real Kotlin-compiled pipelines: which class entries let a pipeline print text
 * and read standard input, while opening files stays unsafe. The candidate lists below are test
 * data, not the default allow list.
 */
class StdioAllowListTest {
  @TempDir lateinit var tmp: Path

  /** Base packages without any IO package (ADR-013), enough for the samples. */
  private val base =
      listOf(
          AllowListEntry("java.lang"),
          AllowListEntry("java.util"),
          AllowListEntry("kotlin", exactOnly = true),
          AllowListEntry("kotlin.jvm"),
          AllowListEntry("kotlin.jvm.internal"),
          AllowListEntry("kotlin.collections"),
          AllowListEntry("kotlin.text"),
          AllowListEntry("org.jetbrains.annotations"),
      )
  private val printStream = ClassEntry("java.io.PrintStream")
  private val console = ClassEntry("kotlin.io.ConsoleKt")

  private fun reasonsOf(sample: Class<*>, vararg extra: AllowListEntry): List<UnsafeReason> =
      SafetyAnalyzer()
          .analyze(KotlinFixtures.jarOf(tmp, "s.jar", sample), AllowList("v1", base + extra))
          .pipelines
          .single()
          .reasons

  private fun violations(reasons: List<UnsafeReason>) =
      reasons.filterIsInstance<UnsafeReason.NotAllowListed>().map { it.className }

  @Test
  fun `printing text is unsafe without the standard output class and safe with it`() {
    assertEquals(
        listOf("java.io.PrintStream"),
        violations(reasonsOf(StdioPrintPipeline::class.java)),
    )
    assertEquals(listOf(), reasonsOf(StdioPrintPipeline::class.java, printStream))
  }

  @Test
  fun `reading standard input with readLine and readln needs the console class`() {
    assertEquals(
        listOf("kotlin.io.ConsoleKt"),
        violations(reasonsOf(StdioReadLinePipeline::class.java, printStream)),
    )
    assertEquals(listOf(), reasonsOf(StdioReadLinePipeline::class.java, printStream, console))
  }

  @Test
  fun `opening a file by name through the allowed standard output class stays unsafe`() {
    val reasons = reasonsOf(StdioOpensFilePipeline::class.java, printStream, console)

    assertEquals(
        listOf("java.io.PrintStream.<init>(Ljava/lang/String;)V"),
        reasons.filterIsInstance<UnsafeReason.IoSensitiveMember>().map { it.member },
    )
  }

  @Test
  fun `file classes of the same package stay unsafe next to the allowed class`() {
    val reasons = reasonsOf(StdioFileClassesPipeline::class.java, printStream, console)

    assertEquals(
        listOf("java.io.File", "java.io.FileInputStream"),
        violations(reasons).filter { it.startsWith("java.io.File") },
    )
  }

  @Test
  fun `reading the JDK input stream directly is not covered by the two class entries`() {
    val reasons = reasonsOf(StdioJdkInputPipeline::class.java, printStream, console)

    assertTrue("java.io.InputStream" in violations(reasons), "$reasons")
  }
}
