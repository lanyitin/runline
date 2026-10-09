package dev.lawlan.runline.accessors.support

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder

/**
 * The temporary directories tests make are gone once the test that made them is over, and the ones
 * all tests share once all tests are over (WI-57): what keeps repeated runs from filling the disk.
 * The tests run in order, each looking at what the one before it left.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TestDirectoriesTest {
  @Test
  @Order(1)
  fun `a directory made for a test is there while the test runs, whatever it is made to hold`() {
    val dir = TestDirectories.forThisTest("test-directories")
    made.add(dir)
    dir.resolve("file").writeText("x")
    val closed = dir.resolve("closed").createDirectories()
    closed.resolve("inner").createDirectories().resolve("file").writeText("x")
    Files.setPosixFilePermissions(closed, PosixFilePermissions.fromString("---------"))
    Files.createSymbolicLink(dir.resolve("link"), outsideFile)
    Files.createSymbolicLink(dir.resolve("link-to-directory"), outsideFile.parent)

    assertTrue(Files.isDirectory(dir))
    assertTrue(Files.isDirectory(shared))
  }

  @Test
  @Order(2)
  fun `it is gone with all it held once that test is over, and what its links led to is not`() {
    assertEquals(listOf(false), made.map { Files.exists(it, LinkOption.NOFOLLOW_LINKS) })
    assertEquals("kept", outsideFile.readText())
    assertEquals(OUTSIDE_PERMISSIONS, Files.getPosixFilePermissions(outsideFile))
    assertEquals(OUTSIDE_PERMISSIONS, Files.getPosixFilePermissions(outsideFile.parent))
  }

  @Test
  @Order(3)
  fun `a directory shared by all tests is still there after a test is over`() {
    assertTrue(Files.isDirectory(shared))
  }

  private companion object {
    val made = mutableListOf<Path>()
    val OUTSIDE_PERMISSIONS = PosixFilePermissions.fromString("r-xr-x---")

    /** Shared by the tests of this class, as a companion's directories are. */
    val shared: Path = TestDirectories.forAllTests("test-directories-shared")

    /** A file outside the directory, reached through links in it. */
    val outsideFile: Path =
        shared.resolve("outside").createDirectories().resolve("kept").also {
          it.writeText("kept")
          Files.setPosixFilePermissions(it, OUTSIDE_PERMISSIONS)
          Files.setPosixFilePermissions(it.parent, OUTSIDE_PERMISSIONS)
        }
  }
}
