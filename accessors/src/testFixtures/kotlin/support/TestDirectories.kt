package dev.lawlan.runline.accessors.support

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * Temporary directories of tests, deleted with all they hold once they are no longer needed
 * (WI-57): what a test makes for itself once that test is over, what all tests share (a companion's
 * certificates, the jars every run is given) once all tests are over. Without this every run of the
 * tests leaves its directories in the system's temporary directory until the disk is full.
 *
 * The deleting is done by [Cleanup], a JUnit extension registered for every test through
 * `META-INF/services` (the build turns on `junit.jupiter.extensions.autodetection.enabled`).
 * Deleting follows no link out of the directory, and opens up a directory a test has closed to its
 * owner before going into it; anything it cannot delete fails the test.
 */
object TestDirectories {
  private val ofThisTest = mutableListOf<Path>()
  private val ofAllTests = mutableListOf<Path>()
  @Volatile private var cleanupRegistered = false

  /** A new directory, deleted once the test running (or about to run) is over. */
  fun forThisTest(prefix: String): Path = made(prefix, ofThisTest)

  /** A new directory for every test of this test process, deleted once all of them are over. */
  fun forAllTests(prefix: String): Path = made(prefix, ofAllTests)

  private fun made(prefix: String, into: MutableList<Path>): Path {
    check(cleanupRegistered) {
      "nothing would delete this directory: TestDirectories.Cleanup is not registered " +
          "(junit.jupiter.extensions.autodetection.enabled)"
    }
    return Files.createTempDirectory(prefix).also { synchronized(this) { into.add(it) } }
  }

  private fun deleteAll(from: MutableList<Path>) {
    val all = synchronized(this) { from.toList().also { from.clear() } }
    all.forEach(::delete)
  }

  private fun delete(path: Path) {
    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
      openToOwner(path)
      Files.list(path).use { it.toList() }.forEach(::delete)
    }
    Files.deleteIfExists(path)
  }

  private fun openToOwner(directory: Path) {
    val permissions = Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS)
    if (!permissions.containsAll(OWNER_ALL)) {
      Files.setPosixFilePermissions(directory, permissions + OWNER_ALL)
    }
  }

  private val OWNER_ALL =
      setOf(
          PosixFilePermission.OWNER_READ,
          PosixFilePermission.OWNER_WRITE,
          PosixFilePermission.OWNER_EXECUTE,
      )

  /**
   * Deletes the directories of a test after it (and its `@AfterEach`), and the shared ones last.
   */
  class Cleanup : BeforeAllCallback, AfterEachCallback {
    override fun beforeAll(context: ExtensionContext) {
      cleanupRegistered = true
      context.root
          .getStore(ExtensionContext.Namespace.create(TestDirectories::class.java))
          .getOrComputeIfAbsent(
              "all tests",
              { ExtensionContext.Store.CloseableResource { deleteAll(ofAllTests) } },
              ExtensionContext.Store.CloseableResource::class.java,
          )
    }

    override fun afterEach(context: ExtensionContext) = deleteAll(ofThisTest)
  }
}
