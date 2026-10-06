package dev.lawlan.runline.engine.artifact

import dev.lawlan.runline.engine.support.PipelineJars
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** The guard is exercised with real compiled jars that are really compressed. */
class JarExpansionGuardTest {
  private val dir: Path = Files.createTempDirectory("jar-guard")

  private fun jar(extra: Map<String, ByteArray> = emptyMap()): Path =
      PipelineJars.build(
          dir,
          "g-${System.nanoTime()}.jar",
          mapOf("demo.Pipe" to PipelineJars.pipeline("demo.Pipe", "p")),
          extra,
      )

  private val limits =
      JarLimits(maxEntries = 100, maxEntryBytes = 1_000_000, maxTotalBytes = 3_000_000)

  @Test
  fun `a normal jar is within the limits`() {
    assertNull(JarExpansionGuard(limits).check(jar()))
  }

  @Test
  fun `a highly compressed entry over the entry limit is refused and names that limit`() {
    // 5 MB of zeros deflates to a few KB: the jar is tiny, what it expands to is not.
    val bomb = jar(mapOf("data/zeros.bin" to ByteArray(5_000_000)))
    assertTrue(Files.size(bomb) < 100_000, "the test jar should be small on disk")

    val violation = JarExpansionGuard(limits).check(bomb)

    assertEquals(JarLimitViolation.EntryTooLarge("data/zeros.bin", 1_000_000), violation)
  }

  @Test
  fun `many entries that are each small but together too large are refused on the total`() {
    val parts = (1..4).associate { "data/part$it.bin" to ByteArray(900_000) }

    val violation = JarExpansionGuard(limits).check(jar(parts))

    assertEquals(JarLimitViolation.TotalTooLarge(3_000_000), violation)
  }

  @Test
  fun `a jar with more entries than allowed is refused on the count`() {
    val many = (1..150).associate { "data/f$it.txt" to "x".toByteArray() }

    val violation = JarExpansionGuard(limits).check(jar(many))

    assertEquals(JarLimitViolation.TooManyEntries(100), violation)
  }

  @Test
  fun `declared sizes are not trusted, the data really inflated is counted`() {
    val bomb = jar(mapOf("data/zeros.bin" to ByteArray(5_000_000)))
    val forged = dir.resolve("forged.jar")
    Files.write(forged, declareUncompressedSize(Files.readAllBytes(bomb), "data/zeros.bin", 10))

    val violation = JarExpansionGuard(limits).check(forged)

    assertEquals(JarLimitViolation.EntryTooLarge("data/zeros.bin", 1_000_000), violation)
  }

  @Test
  fun `inflating stops at the limit instead of expanding the whole entry`() {
    val bomb = jar(mapOf("data/zeros.bin" to ByteArray(200_000_000)))
    val small = JarLimits(maxEntries = 100, maxEntryBytes = 1_000_000, maxTotalBytes = 3_000_000)

    val started = System.nanoTime()
    val violation = JarExpansionGuard(small).check(bomb)
    val millis = (System.nanoTime() - started) / 1_000_000

    assertIs<JarLimitViolation.EntryTooLarge>(violation)
    assertTrue(millis < 2_000, "refusing should not take a full expansion, took $millis ms")
  }

  @Test
  fun `a file that is not a zip is reported as unreadable, not as within limits`() {
    val notZip = Files.write(dir.resolve("plain.jar"), "this is not a zip".toByteArray())

    assertFailsWith<java.io.IOException> { JarExpansionGuard(limits).check(notZip) }
  }

  /** Rewrites the uncompressed size in the central directory record of [entry]. */
  private fun declareUncompressedSize(zip: ByteArray, entry: String, size: Int): ByteArray {
    val out = zip.copyOf()
    val buffer = java.nio.ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    var i = 0
    while (i < out.size - 46) {
      if (buffer.getInt(i) == 0x02014b50) {
        val nameLength = buffer.getShort(i + 28).toInt()
        if (String(out, i + 46, nameLength) == entry) {
          buffer.putInt(i + 24, size)
          return out
        }
      }
      i++
    }
    error("entry $entry not found")
  }
}
