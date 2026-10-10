package dev.lawlan.runline.accessors.jdbc

import dev.lawlan.runline.core.ResourceFailure
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a run cannot do with a `sqlite` resource however it words its SQL (WI-1003): the text goes
 * to SQLite as it is, so the file's confinement to the resource root cannot rest on the text. It
 * rests on the address the Engine builds from the root and on what the connection allows.
 */
class SqliteBoundaryTest {
  private val rig = SqliteRig()
  private val outside = Files.createTempDirectory("sqlite-outside").toRealPath()

  @AfterTest
  fun close() {
    rig.close()
    outside.toFile().deleteRecursively()
  }

  private val host by lazy { rig.host() }

  @Test
  fun `a run cannot attach a file outside the root, or any other file`() {
    rig.update(host, "CREATE TABLE t (a INTEGER)")

    val target = outside.resolve("attached.db")
    val failed = assertFailsWith<Failed> { rig.update(host, "ATTACH DATABASE '$target' AS other") }

    assertEquals(ResourceFailure.SQL_ERROR, failed.failure)
    assertFalse(Files.exists(target))
    assertFailsWith<Failed> {
      rig.update(host, "ATTACH DATABASE '${rig.root.resolve("second.db")}' AS other")
    }
    assertFalse(Files.exists(rig.root.resolve("second.db")))
  }

  @Test
  fun `a run cannot write a copy of the database outside the root`() {
    rig.update(host, "CREATE TABLE t (a INTEGER)")
    rig.update(host, "INSERT INTO t VALUES (1)")

    val target = outside.resolve("copy.db")
    runCatching { rig.update(host, "VACUUM INTO '$target'") }

    assertFalse(Files.exists(target), "VACUUM INTO wrote a file outside the resource root")
  }

  @Test
  fun `a run cannot load code into the engine`() {
    assertFailsWith<Failed> {
      rig.query(host, "SELECT load_extension('/lib/x86_64-linux-gnu/libm.so.6')")
    }
  }

  @Test
  fun `a run has no function that reads or writes a file`() {
    val target = outside.resolve("written.txt")
    assertFailsWith<Failed> { rig.query(host, "SELECT writefile('$target', 'x')") }
    assertFailsWith<Failed> {
      rig.query(host, "SELECT readfile('${rig.root.resolve("digest.db")}')")
    }
    assertFalse(Files.exists(target))
  }

  @Test
  fun `a link in the root that leads out of it does not take the database out of it`() {
    Files.createSymbolicLink(rig.root.resolve("link"), outside)
    val binding = rig.host(path = "link/digest.db")

    val failed = assertFailsWith<Failed> { rig.query(binding, "SELECT 1") }

    assertEquals(ResourceFailure.DENIED, failed.failure)
    assertTrue(Files.list(outside).use { it.count() } == 0L, "something was made outside the root")
  }

  @Test
  fun `a root that is not there leaves the resource without a connection`() {
    val gone = rig.host(path = "digest.db")
    rig.root.toFile().deleteRecursively()

    val failed = assertFailsWith<Failed> { rig.query(gone, "SELECT 1") }

    assertEquals(ResourceFailure.CONNECTION_FAILED, failed.failure)
  }
}
