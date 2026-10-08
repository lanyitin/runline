package dev.lawlan.runline.accessors.support

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.junit.jupiter.api.io.TempDir

/**
 * The permissions of files hold inside [PermissionsEnforced.run] for whoever runs the tests, root
 * included: what the tests of unusable files rely on (WI-57).
 */
class PermissionsEnforcedTest {
  @TempDir lateinit var tmp: Path

  @Test
  fun `a file nobody may write is not writable, and one nobody may read is not readable`() {
    val readOnly = tmp.resolve("read-only").also { it.writeText("x") }
    val writeOnly = tmp.resolve("write-only").also { it.writeText("x") }
    Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("r--r--r--"))
    Files.setPosixFilePermissions(writeOnly, PosixFilePermissions.fromString("-w--w--w-"))

    PermissionsEnforced.run {
      assertFalse(Files.isWritable(readOnly))
      assertFalse(Files.isReadable(writeOnly))
      assertFailsWith<java.nio.file.AccessDeniedException> { readOnly.writeText("y") }
      assertFailsWith<java.nio.file.AccessDeniedException> { Files.readAllBytes(writeOnly) }
    }
  }

  @Test
  fun `a directory nobody may write into takes no new entry`() {
    val dir = tmp.resolve("d").createDirectories()
    Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"))
    try {
      PermissionsEnforced.run {
        assertFalse(Files.isWritable(dir))
        assertFailsWith<java.nio.file.AccessDeniedException> {
          Files.createFile(dir.resolve("new"))
        }
      }
    } finally {
      Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"))
    }
  }

  @Test
  fun `what the code given returns, or throws, is what the call returns or throws`() {
    assertEquals(42, PermissionsEnforced.run { 42 })
    val thrown = assertFailsWith<IllegalStateException> { PermissionsEnforced.run { error("no") } }
    assertEquals("no", thrown.message)
  }
}
