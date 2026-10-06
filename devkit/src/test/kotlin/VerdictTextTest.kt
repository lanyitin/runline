package dev.lawlan.runline.devkit

import dev.lawlan.runline.analyzer.AccessLimitMetadata
import dev.lawlan.runline.analyzer.AllowList
import dev.lawlan.runline.analyzer.AllowListEntry
import dev.lawlan.runline.analyzer.ClassEntry
import dev.lawlan.runline.analyzer.IoCategory
import dev.lawlan.runline.analyzer.PipelineMetadata
import dev.lawlan.runline.analyzer.PipelineSafety
import dev.lawlan.runline.analyzer.SafetyReport
import dev.lawlan.runline.analyzer.UnsafeReason
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VerdictTextTest {
  private fun render(vararg reasons: UnsafeReason, version: String = "v3"): String {
    val metadata =
        PipelineMetadata(
            "hello",
            listOf(),
            listOf(),
            AccessLimitMetadata(true),
            AccessLimitMetadata(true),
            listOf(),
        )
    val safety = PipelineSafety("demo.Hello", metadata, reasons.toList())
    return VerdictText.render(SafetyReport(version, listOf(safety)), safety)
  }

  @Test
  fun `a safe pipeline shows its verdict, name and the allow list version`() {
    val text = render()

    assertTrue("SAFE" in text)
    assertFalse("UNSAFE" in text)
    assertTrue("demo.Hello" in text)
    assertTrue("hello" in text)
    assertTrue("v3" in text)
  }

  @Test
  fun `every reason is listed with its dependency path`() {
    val text =
        render(
            UnsafeReason.UnrestrictedAccess(IoCategory.NETWORK),
            UnsafeReason.NotAllowListed(
                "java.io.File",
                listOf("demo.Hello", "demo.Util", "java.io.File"),
            ),
            UnsafeReason.JvmExit("java.lang.System.exit", listOf("demo.Hello", "lib.Quit")),
            UnsafeReason.UnreadableClass(
                "lib.Broken",
                listOf("demo.Hello", "lib.Broken"),
                "bad magic",
            ),
            UnsafeReason.LimitExceeded("more than 5000 classes"),
        )

    assertTrue("UNSAFE" in text)
    assertTrue("NETWORK" in text)
    assertTrue("java.io.File" in text)
    assertTrue("demo.Hello -> demo.Util -> java.io.File" in text)
    assertTrue("java.lang.System.exit" in text)
    assertTrue("demo.Hello -> lib.Quit" in text)
    assertTrue("lib.Broken" in text && "bad magic" in text)
    assertTrue("more than 5000 classes" in text)
  }

  @Test
  fun `the limitations statement of the analyzer is shown as it is`() {
    val text = render()

    assertTrue(SafetyReport.LIMITATIONS in text)
  }

  @Test
  fun `a JVM exit reason adds a note that an allowed unsafe pipeline can end the whole process`() {
    val withExit = render(UnsafeReason.JvmExit("java.lang.System.exit", listOf("demo.Hello")))
    val without = render(UnsafeReason.UnrestrictedAccess(IoCategory.PROCESSES))

    assertTrue("終止整個程序" in withExit)
    assertFalse("終止整個程序" in without)
  }

  @Test
  fun `an IO sensitive member reason shows the member and its dependency path`() {
    val text =
        render(
            UnsafeReason.IoSensitiveMember(
                "java.lang.ProcessBuilder.start()Ljava/lang/Process;",
                listOf("demo.Hello", "lib.Runner"),
            )
        )

    assertTrue("UNSAFE" in text)
    assertTrue("IO sensitive member" in text)
    assertTrue("java.lang.ProcessBuilder.start()Ljava/lang/Process;" in text)
    assertTrue("demo.Hello -> lib.Runner" in text)
    assertFalse("終止整個程序" in text)
  }

  private fun renderWith(display: AllowListDisplay?): String {
    val metadata =
        PipelineMetadata(
            "hello",
            listOf(),
            listOf(),
            AccessLimitMetadata(false),
            AccessLimitMetadata(false),
            listOf(),
        )
    val safety = PipelineSafety("demo.Hello", metadata, listOf())
    return VerdictText.render(
        SafetyReport(display?.allowList?.version ?: "v", listOf(safety)),
        safety,
        display,
    )
  }

  private val defaultList =
      AllowList(
          "default-9",
          listOf(
              AllowListEntry("kotlin", true),
              AllowListEntry("java.time"),
              ClassEntry("java.io.PrintStream"),
          ),
      )

  @Test
  fun `the default allow list is named as such with its version and entry count`() {
    val text =
        renderWith(AllowListDisplay(AllowListSource.DEFAULT, defaultList, listEntries = false))

    assertTrue("Allow list: default" in text, text)
    assertTrue("default-9" in text, text)
    assertTrue("3 entries" in text, text)
  }

  @Test
  fun `an override is named as such`() {
    val text =
        renderWith(AllowListDisplay(AllowListSource.OVERRIDE, defaultList, listEntries = false))

    assertTrue("Allow list: override" in text, text)
    assertFalse("Allow list: default" in text, text)
  }

  @Test
  fun `the output says it is not the Engine's current allow list`() {
    val text =
        renderWith(AllowListDisplay(AllowListSource.DEFAULT, defaultList, listEntries = false))

    assertTrue("Engine" in text && "not" in text, text)
  }

  @Test
  fun `the entries are listed in full only on request`() {
    val without =
        renderWith(AllowListDisplay(AllowListSource.DEFAULT, defaultList, listEntries = false))
    val with =
        renderWith(AllowListDisplay(AllowListSource.DEFAULT, defaultList, listEntries = true))

    assertFalse("class:java.io.PrintStream" in without, without)
    assertTrue("  kotlin:exact" in with, with)
    assertTrue("  java.time" in with, with)
    assertTrue("  class:java.io.PrintStream" in with, with)
  }

  @Test
  fun `without allow list information the output is as before`() {
    val text = renderWith(null)

    assertFalse("Allow list:" in text, text)
  }
}
