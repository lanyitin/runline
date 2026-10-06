package dev.lawlan.runline.analyzer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * WI-10: whether one entry makes another redundant, so that the Engine can point out duplicates and
 * overlaps without a second copy of the matching rules. It must agree with how the analyzer judges
 * a class.
 */
class AllowListEntryCoverageTest {
  private fun pkg(name: String, exactOnly: Boolean = false) = PackageEntry(name, exactOnly)

  private fun cls(name: String) = ClassEntry(name)

  @Test
  fun `an entry covers an identical entry`() {
    assertTrue(pkg("java.util").covers(pkg("java.util")))
    assertTrue(pkg("java.util", true).covers(pkg("java.util", true)))
    assertTrue(cls("java.io.PrintStream").covers(cls("java.io.PrintStream")))
  }

  @Test
  fun `a package with sub-packages covers its sub-packages and classes`() {
    val util = pkg("java.util")
    assertTrue(util.covers(pkg("java.util.concurrent")))
    assertTrue(util.covers(pkg("java.util.concurrent", exactOnly = true)))
    assertTrue(util.covers(pkg("java.util", exactOnly = true)))
    assertTrue(util.covers(cls("java.util.List")))
    assertTrue(util.covers(cls("java.util.concurrent.atomic.AtomicInteger")))
  }

  @Test
  fun `a this-package-only entry covers only that package, not its sub-packages`() {
    val util = pkg("java.util", exactOnly = true)
    assertTrue(util.covers(cls("java.util.List")))
    assertTrue(util.covers(cls("java.util.Map\$Entry")))
    assertFalse(util.covers(pkg("java.util")), "the whole package is more than this one")
    assertFalse(util.covers(pkg("java.util.concurrent")))
    assertFalse(util.covers(cls("java.util.concurrent.Executor")))
  }

  @Test
  fun `a package does not cover a package with a similar name`() {
    assertFalse(pkg("java.util").covers(pkg("java.utility")))
    assertFalse(pkg("java.util").covers(pkg("java")))
    assertFalse(pkg("java.util").covers(cls("java.utility.Thing")))
  }

  @Test
  fun `a class covers itself and its nested classes only`() {
    val out = cls("java.io.PrintStream")
    assertTrue(out.covers(cls("java.io.PrintStream\$Inner")))
    assertFalse(out.covers(cls("java.io.PrintStreamX")))
    assertFalse(out.covers(cls("java.io.File")))
    assertFalse(out.covers(pkg("java.io")))
    assertFalse(out.covers(pkg("java.io.PrintStream")))
  }

  @Test
  fun `covering agrees with how the analyzer classifies a class`() {
    val entries =
        listOf(
            pkg("java.util"),
            pkg("java.util", true),
            pkg("java.io"),
            cls("java.io.PrintStream"),
            cls("com.acme.Outer"),
        )
    val classes =
        listOf(
            "java.util.List",
            "java.util.concurrent.Executor",
            "java.io.File",
            "java.io.PrintStream",
            "java.io.PrintStream\$1",
            "java.io.PrintStreamX",
            "com.acme.Outer",
            "com.acme.Outer\$Inner",
            "com.acme.Other",
            "Plain",
        )
    for (entry in entries) {
      for (name in classes) {
        val classified =
            TreeRules.classify(name, "p.Start", AllowList("v", listOf(entry))) == NodeKind.IGNORED
        assertEquals(classified, entry.coversClass(name), "$entry $name")
      }
    }
  }
}
