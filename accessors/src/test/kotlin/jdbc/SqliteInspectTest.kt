package dev.lawlan.runline.accessors.jdbc

import dev.lawlan.runline.accessors.FileProblem
import dev.lawlan.runline.accessors.support.PermissionsEnforced
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * What stops the file of a `sqlite` resource from being used, as the file system says it (WI-1003):
 * the same categories as for a `file` resource, except that the directory of the file is never
 * made, so one that is not there is a problem of its own.
 */
class SqliteInspectTest {
  private val root: Path = Files.createTempDirectory("sqlite-inspect").toRealPath()
  private val profile = SqliteProfile(root)

  @AfterTest
  fun clean() {
    // Permissions are given back so that the directory can be removed.
    root.toFile().walkTopDown().forEach {
      it.setWritable(true)
      it.setReadable(true)
      it.setExecutable(true)
    }
    root.toFile().deleteRecursively()
  }

  private fun inspect(path: String) = profile.inspect(JdbcLocation.LocalFile(path))

  private fun chmod(path: Path, mode: String) =
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode))

  @Test
  fun `a file that is not there yet, in a directory that is, is usable`() {
    assertNull(inspect("digest.db"))
    root.resolve("data").createDirectories()
    assertNull(inspect("data/digest.db"))
  }

  @Test
  fun `a directory that is not there is not made, so the file cannot be`() {
    assertEquals(FileProblem.PARENT_NOT_CREATABLE, inspect("data/digest.db"))
    assertEquals(false, Files.exists(root.resolve("data")))
  }

  @Test
  fun `a root that is not there or is not a directory is unavailable`() {
    val gone = SqliteProfile(root.resolve("gone"))
    val file = root.resolve("file").also { it.writeText("x") }

    assertEquals(FileProblem.ROOT_UNAVAILABLE, gone.inspect(JdbcLocation.LocalFile("a.db")))
    assertEquals(
        FileProblem.ROOT_UNAVAILABLE,
        SqliteProfile(file).inspect(JdbcLocation.LocalFile("a.db")),
    )
  }

  @Test
  fun `a link that leads out of the root is outside it`() {
    val outside = Files.createTempDirectory("sqlite-inspect-outside")
    try {
      root.resolve("link").createSymbolicLinkPointingTo(outside)
      root.resolve("file-link").createSymbolicLinkPointingTo(outside.resolve("x.db"))

      assertEquals(FileProblem.PATH_OUTSIDE_ROOT, inspect("link/digest.db"))
      assertEquals(FileProblem.PATH_OUTSIDE_ROOT, inspect("file-link"))
    } finally {
      outside.toFile().deleteRecursively()
    }
  }

  @Test
  fun `a file that cannot be both read and written, or is a directory, is not usable`() =
      PermissionsEnforced.run {
        val file = root.resolve("digest.db").also { it.writeText("x") }
        chmod(file, "r--r--r--")
        if (Files.isWritable(file))
            fail("permissions are not enforced here, so this proves nothing")
        root.resolve("dir.db").createDirectories()

        assertEquals(FileProblem.NOT_READABLE_WRITABLE, inspect("digest.db"))
        assertEquals(FileProblem.NOT_READABLE_WRITABLE, inspect("dir.db"))
      }

  @Test
  fun `a directory nobody may write into cannot take the files SQLite keeps beside the database`() =
      PermissionsEnforced.run {
        val directory = root.resolve("d").createDirectories()
        chmod(directory, "r-xr-xr-x")
        if (Files.isWritable(directory))
            fail("permissions are not enforced here, so this proves nothing")

        assertEquals(FileProblem.PARENT_NOT_CREATABLE, inspect("d/digest.db"))
      }
}
