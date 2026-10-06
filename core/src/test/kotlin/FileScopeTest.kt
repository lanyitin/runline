package dev.lawlan.runline.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class FileScopeTest {
  @TempDir lateinit var tmp: Path

  private lateinit var shared: Path
  private lateinit var run: Path
  private lateinit var outside: Path

  private fun meta(vararg files: Pair<FileScope, FileMode>) =
      PipelineMetadata(
          name = "demo",
          parameters = emptyList(),
          files = mapOf(*files),
          network = AccessPolicy.Unrestricted,
          processes = AccessPolicy.Unrestricted,
          resources = emptySet(),
      )

  private fun context(m: PipelineMetadata): PipelineContext {
    shared = tmp.resolve("shared").createDirectories()
    run = tmp.resolve("run").createDirectories()
    outside = tmp.resolve("outside").createDirectories()
    return RestrictedContext(m, emptyMap(), shared, run)
  }

  private val rw =
      meta(
          FileScope.PIPELINE_SHARED to FileMode.READ_ONLY,
          FileScope.RUN_PRIVATE to FileMode.READ_WRITE,
      )

  private fun denied(block: () -> Unit): PipelineAccessDenied {
    val e = assertFailsWith<PipelineAccessDenied>(block = block)
    assertEquals(IoCategory.FILE, e.category)
    assertTrue(e.message!!.contains("demo"), "message names the pipeline: ${e.message}")
    return e
  }

  @Test
  fun `writes and reads inside a writable scope`() {
    val ctx = context(rw)
    ctx.files.writeText(
        FileScope.RUN_PRIVATE,
        "out/a.txt".also { run.resolve("out").createDirectories() },
        "hi",
    )

    assertEquals("hi", ctx.files.readText(FileScope.RUN_PRIVATE, "out/a.txt"))
    assertEquals("hi", run.resolve("out/a.txt").readText())
    assertTrue(ctx.files.exists(FileScope.RUN_PRIVATE, "out/a.txt"))
    assertEquals(listOf("a.txt"), ctx.files.list(FileScope.RUN_PRIVATE, "out"))
    ctx.files.delete(FileScope.RUN_PRIVATE, "out/a.txt")
    assertFalse(ctx.files.exists(FileScope.RUN_PRIVATE, "out/a.txt"))
  }

  @Test
  fun `reads from a read-only scope but rejects writes and deletes`() {
    val ctx = context(rw)
    shared.resolve("cfg.txt").writeText("v")

    assertEquals("v", ctx.files.readText(FileScope.PIPELINE_SHARED, "cfg.txt"))
    denied { ctx.files.writeText(FileScope.PIPELINE_SHARED, "cfg.txt", "x") }
    denied { ctx.files.delete(FileScope.PIPELINE_SHARED, "cfg.txt") }
    assertEquals("v", shared.resolve("cfg.txt").readText())
  }

  @Test
  fun `an undeclared scope is unavailable`() {
    val ctx = context(meta(FileScope.RUN_PRIVATE to FileMode.READ_WRITE))
    denied { ctx.files.readText(FileScope.PIPELINE_SHARED, "x") }
    denied { ctx.files.exists(FileScope.PIPELINE_SHARED, "x") }
  }

  @Test
  fun `absolute paths are rejected`() {
    val ctx = context(rw)
    outside.resolve("secret").writeText("s")
    denied { ctx.files.readText(FileScope.RUN_PRIVATE, outside.resolve("secret").toString()) }
  }

  @Test
  fun `relative paths escaping the scope are rejected, but dot-dot staying inside is fine`() {
    val ctx = context(rw)
    run.resolve("a").createDirectories()
    run.resolve("b.txt").writeText("b")
    outside.resolve("secret").writeText("s")

    assertEquals("b", ctx.files.readText(FileScope.RUN_PRIVATE, "a/../b.txt"))
    denied { ctx.files.readText(FileScope.RUN_PRIVATE, "../outside/secret") }
    denied { ctx.files.writeText(FileScope.RUN_PRIVATE, "a/../../x", "x") }
  }

  @Test
  fun `symlinks leaving the scope are rejected for read, write and list`() {
    val ctx = context(rw)
    outside.resolve("secret").writeText("s")
    run.resolve("link").createSymbolicLinkPointingTo(outside)
    run.resolve("filelink").createSymbolicLinkPointingTo(outside.resolve("secret"))

    denied { ctx.files.readText(FileScope.RUN_PRIVATE, "link/secret") }
    denied { ctx.files.readText(FileScope.RUN_PRIVATE, "filelink") }
    denied { ctx.files.writeText(FileScope.RUN_PRIVATE, "link/new.txt", "x") }
    denied { ctx.files.list(FileScope.RUN_PRIVATE, "link") }
    assertFalse(Files.exists(outside.resolve("new.txt")))
  }

  @Test
  fun `symlinks staying inside the scope are allowed`() {
    val ctx = context(rw)
    run.resolve("real.txt").writeText("r")
    run.resolve("alias").createSymbolicLinkPointingTo(run.resolve("real.txt"))

    assertEquals("r", ctx.files.readText(FileScope.RUN_PRIVATE, "alias"))
  }
}
