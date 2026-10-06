package dev.lawlan.runline.core

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class FileQuotaTest {
  @TempDir lateinit var tmp: Path

  private val metadata =
      PipelineMetadata(
          name = "demo",
          parameters = emptyList(),
          files =
              mapOf(
                  FileScope.PIPELINE_SHARED to FileMode.READ_WRITE,
                  FileScope.RUN_PRIVATE to FileMode.READ_WRITE,
              ),
          network = AccessPolicy.Unrestricted,
          processes = AccessPolicy.Unrestricted,
          resources = emptySet(),
      )

  private fun context(maxBytes: Long?): PipelineContext {
    val shared = tmp.resolve("shared").createDirectories()
    val run = tmp.resolve("run").createDirectories()
    return RestrictedContext(metadata, emptyMap(), shared, run, maxBytes)
  }

  @Test
  fun `write within the limit succeeds`() {
    val ctx = context(maxBytes = 10)

    ctx.files.writeText(FileScope.PIPELINE_SHARED, "a.txt", "0123456789")

    assertEquals("0123456789", tmp.resolve("shared/a.txt").readText())
  }

  @Test
  fun `write that would exceed the limit is rejected with a clear error and writes nothing`() {
    val ctx = context(maxBytes = 10)
    ctx.files.writeText(FileScope.PIPELINE_SHARED, "a.txt", "123456")

    val e =
        assertFailsWith<FileQuotaExceeded> {
          ctx.files.writeText(FileScope.PIPELINE_SHARED, "b.txt", "12345")
        }

    assertEquals(IoCategory.FILE, e.category)
    assertTrue(e.message!!.contains("demo"), e.message)
    assertTrue(e.message!!.contains("PIPELINE_SHARED"), e.message)
    assertTrue(e.message!!.contains("10"), e.message)
    assertFalse(ctx.files.exists(FileScope.PIPELINE_SHARED, "b.txt"))
  }

  @Test
  fun `overwriting a file counts only the size difference`() {
    val ctx = context(maxBytes = 10)
    ctx.files.writeText(FileScope.PIPELINE_SHARED, "a.txt", "0123456789")

    ctx.files.writeText(FileScope.PIPELINE_SHARED, "a.txt", "abcdefghij")

    assertEquals("abcdefghij", tmp.resolve("shared/a.txt").readText())
  }

  @Test
  fun `the limit applies to each scope separately`() {
    val ctx = context(maxBytes = 5)
    ctx.files.writeText(FileScope.PIPELINE_SHARED, "a.txt", "12345")

    ctx.files.writeText(FileScope.RUN_PRIVATE, "a.txt", "12345")

    assertTrue(ctx.files.exists(FileScope.RUN_PRIVATE, "a.txt"))
  }

  @Test
  fun `without a limit writes are not restricted`() {
    val ctx = context(maxBytes = null)

    ctx.files.writeText(FileScope.PIPELINE_SHARED, "a.txt", "x".repeat(1000))

    assertTrue(ctx.files.exists(FileScope.PIPELINE_SHARED, "a.txt"))
  }
}
