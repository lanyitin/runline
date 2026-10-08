package dev.lawlan.runline.engine.secret

import dev.lawlan.runline.accessors.support.PermissionsEnforced
import dev.lawlan.runline.engine.support.CapturedLogs
import dev.lawlan.runline.engine.support.Keystores
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.Locale
import kotlin.test.*

/** The store over a real keystore: lookup, reload and what it leaves alone (WI-41). */
class KeystoreSecretStoreTest {
  private val keystores = Keystores()
  private val passwordFile = keystores.passwordFile()
  private val password = SecretValue(Keystores.DEFAULT_PASSWORD)

  private fun open(file: Path) = KeystoreSecretStore.open(file, password)

  private fun SecretStore.value(alias: String) =
      assertIs<SecretLookup.Found>(lookup(alias)).value.reveal()

  /** What an operator does: a copy changed with the tool, then renamed over the original. */
  private fun replaceWith(file: Path, change: (Path) -> Unit) {
    val copy = file.resolveSibling(file.fileName.toString() + ".new")
    Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING)
    change(copy)
    Files.move(copy, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
  }

  @Test
  fun `an alias is found whatever its case, also under a Turkish default locale`() {
    val file = keystores.pkcs12("case.p12", mapOf("Issuer-ID" to "value-1"))
    val before = Locale.getDefault()
    Locale.setDefault(Locale.forLanguageTag("tr-TR"))
    try {
      val store = open(file)

      assertEquals("value-1", store.value("ISSUER-ID"))
      assertEquals("value-1", store.value("issuer-id"))
      assertEquals("value-1", store.value("Issuer-Id"))
      assertEquals(listOf("issuer-id"), store.entries().map { it.alias })
    } finally {
      Locale.setDefault(before)
    }
  }

  @Test
  fun `an alias that is not there is not found`() {
    val store = open(keystores.pkcs12("none.p12", mapOf("a" to "value")))

    assertEquals(SecretLookup.Missing, store.lookup("b"))
    assertEquals(SecretLookup.Missing, store.lookup(""))
    assertTrue(store.configured)
  }

  @Test
  fun `without a keystore nothing is found and nothing can be reloaded`() {
    val store: SecretStore = NoSecretStore

    assertFalse(store.configured)
    assertEquals(SecretLookup.Missing, store.lookup("a"))
    assertEquals(emptyList(), store.entries())
    assertEquals(ReloadResult.NotConfigured, store.reload())
  }

  @Test
  fun `a reload names the aliases that were added, removed or changed, and no other`() {
    val file =
        keystores.pkcs12(
            "reload.p12",
            mapOf("kept" to "same", "changed" to "old-value", "removed" to "gone-soon"),
        )
    val store = open(file)

    replaceWith(file) {
      keystores.deleteEntry(it, "changed", passwordFile)
      keystores.importSecret(it, "changed", "new-value", passwordFile)
      keystores.deleteEntry(it, "removed", passwordFile)
      keystores.importSecret(it, "added", "fresh", passwordFile)
    }
    val result = store.reload()

    assertEquals(ReloadResult.Reloaded(3, listOf("added", "changed", "removed")), result)
    assertEquals("new-value", store.value("changed"))
    assertEquals("fresh", store.value("added"))
    assertEquals("same", store.value("kept"))
    assertEquals(SecretLookup.Missing, store.lookup("removed"))
  }

  @Test
  fun `a reload of a file that did not change says nothing changed`() {
    val store = open(keystores.pkcs12("same.p12", mapOf("a" to "value")))

    assertEquals(ReloadResult.Reloaded(1, emptyList()), store.reload())
  }

  @Test
  fun `a trusted certificate that is replaced by another counts as changed`() {
    val file = keystores.pkcs12("certs.p12", mapOf("a" to "value"))
    keystores.trustedCertificate(file, "ca", passwordFile)
    val store = open(file)

    replaceWith(file) {
      keystores.deleteEntry(it, "ca", passwordFile)
      keystores.trustedCertificate(it, "ca", passwordFile)
    }

    assertEquals(ReloadResult.Reloaded(2, listOf("ca")), store.reload())
  }

  @Test
  fun `a reload that fails keeps what was in memory, in every category`() {
    val file = keystores.pkcs12("keep.p12", mapOf("a" to "value-a"))
    val store = open(file)
    val bytes = Files.readAllBytes(file)

    Files.write(file, bytes.copyOf(bytes.size / 2))
    assertEquals(ReloadResult.Failed(OpenFailure.CORRUPT), store.reload())
    Files.writeString(file, "not a keystore\n".repeat(20))
    assertEquals(ReloadResult.Failed(OpenFailure.WRONG_FORMAT), store.reload())
    Files.delete(file)
    assertEquals(ReloadResult.Failed(OpenFailure.FILE_MISSING), store.reload())

    assertEquals("value-a", store.value("a"))
    assertEquals(listOf("a"), store.entries().map { it.alias })
  }

  @Test
  fun `the file is opened read only, so a read only file in a read only directory works and is untouched`() =
      // As for any user, also for root (WI-57): a store that opened the file for writing fails
      // here.
      PermissionsEnforced.run {
        val dir = Files.createDirectory(keystores.dir.resolve("mounted"))
        val file = keystores.pkcs12("seed.p12", mapOf("a" to "value-a"))
        val mounted = Files.copy(file, dir.resolve("keystore.p12"))
        Files.setPosixFilePermissions(mounted, PosixFilePermissions.fromString("r--------"))
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-x------"))
        val bytes = Files.readAllBytes(mounted)
        val modified = Files.getLastModifiedTime(mounted)
        try {
          // Otherwise a store that opened the file for writing would pass as well.
          check(!Files.isWritable(mounted) && !Files.isWritable(dir)) {
            "permissions are not enforced here, so the test would prove nothing"
          }
          val store = open(mounted)
          assertEquals("value-a", store.value("a"))
          assertEquals(ReloadResult.Reloaded(1, emptyList()), store.reload())

          assertContentEquals(bytes, Files.readAllBytes(mounted))
          assertEquals(modified, Files.getLastModifiedTime(mounted))
          assertEquals(
              listOf("keystore.p12"),
              Files.list(dir).use { it.map { p -> p.fileName.toString() }.toList() },
          )
        } finally {
          Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
        }
      }

  @Test
  fun `opening a keystore that cannot be opened throws the category`() {
    val failure =
        assertFailsWith<KeystoreOpenException> { open(keystores.dir.resolve("absent.p12")) }

    assertEquals(OpenFailure.FILE_MISSING, failure.failure)
  }

  @Test
  fun `a keystore other users can read is warned about, without the password, and one they cannot is not`() {
    val file = keystores.pkcs12("perm.p12", mapOf("a" to "value"))

    fun warnings(mode: String): List<String> {
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(mode))
      return CapturedLogs().use { logs ->
        open(file)
        logs.lines.filter { it.startsWith("WARN") }
      }
    }

    val open = warnings("rw-r--r--")
    assertEquals(1, open.size, "$open")
    assertTrue(open.single().contains("readable by other users"), open.single())
    assertFalse(open.single().contains(Keystores.DEFAULT_PASSWORD))
    assertEquals(emptyList(), warnings("rw-------"))
    assertEquals(emptyList(), warnings("rw-r-----"))
    assertEquals(1, warnings("rw----r--").size)
  }
}
