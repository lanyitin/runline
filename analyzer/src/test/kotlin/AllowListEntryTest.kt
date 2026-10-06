package dev.lawlan.runline.analyzer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** WI-24 / ADR-014: the kind of an entry is explicit and its name is validated. */
class AllowListEntryTest {
  @Test
  fun `the two kinds are different entries even for the same name`() {
    assertNotEquals<AllowListEntry>(
        PackageEntry("java.io.PrintStream"),
        ClassEntry("java.io.PrintStream"),
    )
  }

  @Test
  fun `the legacy constructor form still makes package entries`() {
    assertEquals<AllowListEntry>(PackageEntry("kotlin"), AllowListEntry("kotlin"))
    assertEquals<AllowListEntry>(
        PackageEntry("kotlin", true),
        AllowListEntry("kotlin", exactOnly = true),
    )
  }

  @Test
  fun `valid package and class names are accepted`() {
    PackageEntry("kotlin")
    PackageEntry("java.util.function", exactOnly = true)
    ClassEntry("java.io.PrintStream")
    ClassEntry("com.acme.Outer\$Inner")
  }

  @Test
  fun `invalid package names are rejected naming the entry`() {
    listOf("", " ", "java..io", ".java", "java.", "java.io ", "java/io", "1java", "java.io.*")
        .forEach {
          val error = assertFailsWith<IllegalArgumentException>("'$it'") { PackageEntry(it) }
          assertTrue("package" in error.message!!, error.message)
          assertTrue("'$it'" in error.message!!, error.message)
        }
  }

  @Test
  fun `invalid class names are rejected naming the entry`() {
    listOf(
            "",
            "PrintStream",
            "java..PrintStream",
            "java.io.",
            "java.io.Print Stream",
            "java/io/PrintStream",
            "java.io.*",
        )
        .forEach {
          val error = assertFailsWith<IllegalArgumentException>("'$it'") { ClassEntry(it) }
          assertTrue("class" in error.message!!, error.message)
          assertTrue("'$it'" in error.message!!, error.message)
        }
  }
}
