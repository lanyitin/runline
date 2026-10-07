package dev.lawlan.runline.accessors

import dev.lawlan.runline.core.ResourceFailure
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * The file of a resource never leaves the resource root, however the path is spelled and whatever
 * is swapped on the real file system between two accesses (real directories, real symbolic links).
 */
class FileEntityPathsTest {
  @TempDir lateinit var tmp: Path

  private val root: Path by lazy { tmp.resolve("root").createDirectories() }
  private val outside: Path by lazy { tmp.resolve("outside").createDirectories() }

  private fun rejected(block: () -> Unit): ResourceOperationFailure {
    val e = assertFailsWith<ResourceOperationFailure> { block() }
    assertEquals(ResourceFailure.PATH_REJECTED, e.failure)
    assertFalse(e.message!!.contains(tmp.toString()), "no real path in: ${e.message}")
    return e
  }

  @Test
  fun `a nested path inside the root works and creates its directories`() {
    val entity = FileEntity(root, "a/b/c.txt")

    entity.writeBytes("hi".toByteArray())

    assertEquals("hi", entity.readBytes().decodeToString())
    assertEquals("hi", root.resolve("a/b/c.txt").readText())
  }

  @Test
  fun `a path that climbs out of the root is rejected and nothing is written`() {
    rejected { FileEntity(root, "../outside/x.txt").writeBytes(ByteArray(1)) }
    rejected { FileEntity(root, "a/../../outside/x.txt").readBytes() }

    assertFalse(outside.resolve("x.txt").exists())
  }

  @Test
  fun `an absolute path is rejected`() {
    val target = outside.resolve("x.txt")

    rejected { FileEntity(root, target.toString()).writeBytes(ByteArray(1)) }

    assertFalse(target.exists())
  }

  @Test
  fun `a symbolic link to a directory outside the root does not redirect the file`() {
    root.resolve("link").createSymbolicLinkPointingTo(outside)

    rejected { FileEntity(root, "link/x.txt").writeBytes(ByteArray(1)) }
    rejected { FileEntity(root, "link/deeper/x.txt").writeBytes(ByteArray(1)) }

    assertFalse(outside.resolve("x.txt").exists())
    assertFalse(outside.resolve("deeper").exists(), "no directory was made outside either")
  }

  @Test
  fun `a symbolic link as the file itself is not followed, not even inside the root`() {
    outside.resolve("target.txt").writeText("secret")
    root.resolve("f.txt").createSymbolicLinkPointingTo(outside.resolve("target.txt"))
    root.resolve("real.txt").writeText("inside")
    root.resolve("alias.txt").createSymbolicLinkPointingTo(root.resolve("real.txt"))

    rejected { FileEntity(root, "f.txt").readBytes() }
    rejected { FileEntity(root, "f.txt").writeBytes("x".toByteArray()) }
    rejected { FileEntity(root, "alias.txt").writeBytes("x".toByteArray()) }

    assertEquals("secret", outside.resolve("target.txt").readText())
    assertEquals("inside", root.resolve("real.txt").readText())
  }

  @Test
  fun `a directory swapped for a link between two accesses is caught at the second`() {
    val entity = FileEntity(root, "d/x.txt")
    entity.writeBytes("first".toByteArray())

    root.resolve("d/x.txt").toFile().delete()
    Files.delete(root.resolve("d"))
    root.resolve("d").createSymbolicLinkPointingTo(outside)

    rejected { entity.writeBytes("second".toByteArray()) }
    rejected { entity.readBytes() }
    assertFalse(outside.resolve("x.txt").exists())
  }

  @Test
  fun `a root that is itself reached through a link still works for paths inside it`() {
    val alias = tmp.resolve("alias-root")
    alias.createSymbolicLinkPointingTo(root)

    val entity = FileEntity(alias, "x.txt")
    entity.writeBytes("ok".toByteArray())

    assertTrue(root.resolve("x.txt").exists())
    assertEquals("ok", entity.readBytes().decodeToString())
  }
}
