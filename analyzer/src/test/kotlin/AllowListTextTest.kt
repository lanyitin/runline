package dev.lawlan.runline.analyzer

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WI-25: the one text form of an allow list (comma separated; `package`, `package:exact`,
 * `class:full.Name`) that the Engine's configuration and the development entry point both read and
 * write. The property tests use a fixed seed so a failure repeats.
 */
class AllowListTextTest {
  // ---- reading ----

  @Test
  fun `reads packages, exact-only packages and classes, trimming blanks and skipping empty items`() {
    val parsed =
        AllowListText.parse("java.lang, kotlin:exact ,,class:java.io.PrintStream , com.acme")

    assertEquals(emptyList(), parsed.problems)
    assertEquals(
        listOf(
            PackageEntry("java.lang"),
            PackageEntry("kotlin", exactOnly = true),
            ClassEntry("java.io.PrintStream"),
            PackageEntry("com.acme"),
        ),
        parsed.entries,
    )
  }

  @Test
  fun `empty or blank text is the empty list`() {
    assertEquals(emptyList(), AllowListText.parse("").entries)
    assertEquals(emptyList(), AllowListText.parse("  , ").entries)
    assertTrue(AllowListText.parse("").problems.isEmpty())
  }

  @Test
  fun `a nested class name is kept and a bare dotted name is always a package`() {
    val parsed = AllowListText.parse("class:com.acme.Out\$In,java.io.PrintStream")

    assertEquals(
        listOf(ClassEntry("com.acme.Out\$In"), PackageEntry("java.io.PrintStream")),
        parsed.entries,
    )
  }

  // ---- rejecting ----

  @Test
  fun `invalid entries are reported one by one with the entry and a reason, valid ones are kept`() {
    val parsed =
        AllowListText.parse("java.lang,class:PrintStream,java..io,class:,kotlin:exact:exact,:exact")

    assertEquals(listOf(PackageEntry("java.lang")), parsed.entries)
    assertEquals(
        listOf("class:PrintStream", "java..io", "class:", "kotlin:exact:exact", ":exact"),
        parsed.problems.map { it.entry },
    )
    assertTrue(parsed.problems.all { it.reason.isNotBlank() })
  }

  @Test
  fun `a class entry cannot be exact-only`() {
    val problem = AllowListText.parse("class:java.io.PrintStream:exact").problems.single()

    assertEquals("class:java.io.PrintStream:exact", problem.entry)
  }

  @Test
  fun `the earlier trailing bang form is rejected and the reason names the exact form`() {
    for (old in listOf("kotlin!", "java.util!", "class:java.io.PrintStream!")) {
      val problem = AllowListText.parse("java.lang,$old").problems.single()

      assertEquals(old, problem.entry)
      assertTrue(":exact" in problem.reason, problem.reason)
    }
  }

  @Test
  fun `wildcards and whitespace inside a name are rejected`() {
    for (bad in listOf("java.io.*", "java. io", "java.lang;java.util", "a-b.c")) {
      assertEquals(listOf(bad), AllowListText.parse(bad).problems.map { it.entry }, bad)
    }
  }

  // ---- writing ----

  @Test
  fun `writes each kind in the form it is read`() {
    assertEquals("kotlin:exact", AllowListText.formatEntry(PackageEntry("kotlin", true)))
    assertEquals("java.time", AllowListText.formatEntry(PackageEntry("java.time")))
    assertEquals(
        "class:java.io.PrintStream",
        AllowListText.formatEntry(ClassEntry("java.io.PrintStream")),
    )
    assertEquals(
        "kotlin:exact,java.time,class:java.io.PrintStream",
        AllowListText.format(
            listOf(
                PackageEntry("kotlin", true),
                PackageEntry("java.time"),
                ClassEntry("java.io.PrintStream"),
            )
        ),
    )
    assertEquals("", AllowListText.format(emptyList()))
  }

  // ---- properties ----

  @Test
  fun `any valid list written and read again is the same list`() {
    val random = Random(25)
    repeat(500) { round ->
      val list = List(random.nextInt(0, 12)) { randomEntry(random) }

      val parsed = AllowListText.parse(AllowListText.format(list))

      assertEquals(emptyList(), parsed.problems, "round $round: $list")
      assertEquals(list, parsed.entries, "round $round")
    }
  }

  @Test
  fun `any entry written is read back as that one entry, however it is padded`() {
    val random = Random(26)
    repeat(500) {
      val entry = randomEntry(random)
      val padded = " ".repeat(random.nextInt(3)) + AllowListText.formatEntry(entry)

      assertEquals(listOf(entry), AllowListText.parse(padded + " ,").entries)
    }
  }

  @Test
  fun `a list with one corrupted entry is reported at exactly that entry`() {
    val random = Random(27)
    val corruptions = listOf("!", "..", "*", " x", "class:", ":exact:exact")
    repeat(300) {
      val good = List(random.nextInt(0, 6)) { AllowListText.formatEntry(randomEntry(random)) }
      val bad = "a.b" + corruptions.random(random)
      val position = random.nextInt(good.size + 1)
      val text = (good.take(position) + bad + good.drop(position)).joinToString(",")

      val parsed = AllowListText.parse(text)

      assertEquals(listOf(bad.trim()), parsed.problems.map { it.entry }, text)
      assertEquals(good.size, parsed.entries.size, text)
    }
  }

  private fun randomEntry(random: Random): AllowListEntry {
    val name = randomName(random)
    return when (random.nextInt(3)) {
      0 -> PackageEntry(name)
      1 -> PackageEntry(name, exactOnly = true)
      else -> ClassEntry("$name.${randomSegment(random, classLike = true)}")
    }
  }

  private fun randomName(random: Random) =
      List(random.nextInt(1, 4)) { randomSegment(random) }.joinToString(".")

  private fun randomSegment(random: Random, classLike: Boolean = false): String {
    val head = if (classLike) "ABCDEFGH" else "abcdefgh_"
    val tail = "abcdefghXYZ_019$"
    return head.random(random).toString() +
        String(CharArray(random.nextInt(0, 6)) { tail.random(random) })
  }
}
