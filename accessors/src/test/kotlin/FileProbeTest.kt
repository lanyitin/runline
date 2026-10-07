package dev.lawlan.runline.accessors

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.fail
import org.junit.jupiter.api.io.TempDir

/** Whether the file of a resource can be used, judged on the real file system. */
class FileProbeTest {
  @TempDir lateinit var tmp: Path

  private val root: Path by lazy { tmp.resolve("root").createDirectories() }

  private val changed = mutableListOf<Path>()

  @AfterTest
  fun restore() {
    changed.forEach {
      Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwxrwxrwx"))
    }
  }

  private fun chmod(path: Path, mode: String) {
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode))
    changed.add(path)
  }

  /**
   * A permission that is not enforced (a privileged user) would make the test below meaningless.
   */
  private fun requireEnforced(denied: Boolean) {
    if (!denied) fail("permissions are not enforced for this user: run as an unprivileged user")
  }

  @Test
  fun `a file that does not exist yet in a directory that does is usable`() {
    assertNull(FileProbe.check(root, "out.txt"))
  }

  @Test
  fun `a file in directories that can be made is usable`() {
    assertNull(FileProbe.check(root, "a/b/out.txt"))
    assertFalse(Files.exists(root.resolve("a")), "looking does not make anything")
  }

  @Test
  fun `an existing file that can be read and written is usable`() {
    root.resolve("out.txt").writeText("x")

    assertNull(FileProbe.check(root, "out.txt"))
  }

  @Test
  fun `a root that is missing or is not a directory is unavailable`() {
    assertEquals(FileProblem.ROOT_UNAVAILABLE, FileProbe.check(tmp.resolve("gone"), "out.txt"))
    tmp.resolve("plain").writeText("x")
    assertEquals(FileProblem.ROOT_UNAVAILABLE, FileProbe.check(tmp.resolve("plain"), "out.txt"))
  }

  @Test
  fun `a directory that cannot be made, because something else is in the way, is reported as that`() {
    root.resolve("d").writeText("a file where a directory should be")

    assertEquals(FileProblem.PARENT_NOT_CREATABLE, FileProbe.check(root, "d/out.txt"))
  }

  @Test
  fun `a directory nobody may write into cannot take the file`() {
    val d = root.resolve("d").createDirectories()
    chmod(d, "r-xr-xr-x")
    requireEnforced(!Files.isWritable(d))

    assertEquals(FileProblem.PARENT_NOT_CREATABLE, FileProbe.check(root, "d/out.txt"))
    assertEquals(FileProblem.PARENT_NOT_CREATABLE, FileProbe.check(root, "d/e/out.txt"))
  }

  @Test
  fun `a file that cannot be both read and written is reported as that`() {
    val file = root.resolve("out.txt").also { it.writeText("x") }
    chmod(file, "r--r--r--")
    requireEnforced(!Files.isWritable(file))
    assertEquals(FileProblem.NOT_READABLE_WRITABLE, FileProbe.check(root, "out.txt"))

    chmod(file, "-w--w--w-")
    requireEnforced(!Files.isReadable(file))
    assertEquals(FileProblem.NOT_READABLE_WRITABLE, FileProbe.check(root, "out.txt"))
  }

  @Test
  fun `a path that leaves the root, by name or by a link, is reported as outside`() {
    val outside = tmp.resolve("outside").createDirectories()
    root.resolve("link").createSymbolicLinkPointingTo(outside)

    assertEquals(FileProblem.PATH_OUTSIDE_ROOT, FileProbe.check(root, "../outside/x.txt"))
    assertEquals(
        FileProblem.PATH_OUTSIDE_ROOT,
        FileProbe.check(root, outside.resolve("x.txt").toString()),
    )
    assertEquals(FileProblem.PATH_OUTSIDE_ROOT, FileProbe.check(root, "link/x.txt"))
  }
}
