package dev.lawlan.runline.engine.secret

import dev.lawlan.runline.accessors.tls.ClientCertificate
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory

/** The secret store of an Engine that was given no keystore: nothing is found (WI-41). */
object NoSecretStore : SecretStore {
  override val configured = false

  override fun entries(): List<SecretEntryInfo> = emptyList()

  override fun lookup(alias: String): SecretLookup = SecretLookup.Missing

  override fun trustedCertificate(alias: String): EntryLookup<X509Certificate> = EntryLookup.Missing

  override fun clientCertificate(alias: String): EntryLookup<ClientCertificate> =
      EntryLookup.Missing

  override fun reload(): ReloadResult = ReloadResult.NotConfigured
}

/**
 * Secrets from a PKCS12 keystore, held in memory and read again on request (WI-41). A reload
 * replaces everything at once or, when the file cannot be read, nothing.
 */
class KeystoreSecretStore
private constructor(
    private val loader: KeystoreLoader,
    password: SecretValue,
    first: KeystoreSnapshot,
) : SecretStore, AutoCloseable {
  private val current = AtomicReference(first)

  // The secrets in memory, and the password that opens them, are known to the masking of logs for
  // as long as the store is open.
  private val masking = SecretMasking.register { current.get().secretValues() + password.reveal() }

  override val configured = true

  override fun close() = masking.close()

  override fun entries(): List<SecretEntryInfo> = current.get().entries()

  override fun lookup(alias: String): SecretLookup = current.get().lookup(alias)

  override fun trustedCertificate(alias: String): EntryLookup<X509Certificate> =
      current.get().trustedCertificate(alias)

  override fun clientCertificate(alias: String): EntryLookup<ClientCertificate> =
      current.get().clientCertificate(alias)

  @Synchronized
  override fun reload(): ReloadResult {
    val next =
        try {
          loader.load()
        } catch (e: KeystoreOpenException) {
          return ReloadResult.Failed(e.failure)
        }
    val before = current.getAndSet(next).fingerprints()
    val after = next.fingerprints()
    val changed = (before.keys + after.keys).filter { before[it] != after[it] }.sorted()
    return ReloadResult.Reloaded(after.size, changed)
  }

  companion object {
    private val log = LoggerFactory.getLogger(KeystoreSecretStore::class.java)

    /** Reads the keystore; throws [KeystoreOpenException] when it cannot be opened. */
    fun open(path: Path, password: SecretValue): KeystoreSecretStore {
      val loader = KeystoreLoader(path, password)
      val store = KeystoreSecretStore(loader, password, loader.load())
      warnIfReadableByOthers(path)
      return store
    }

    /**
     * The group may read it (a file of root, read by the service's group, is the usual setup); the
     * rest of the users may not. Where the file system has no such permissions nothing is said.
     */
    private fun warnIfReadableByOthers(path: Path) {
      val permissions =
          try {
            Files.getPosixFilePermissions(path)
          } catch (e: UnsupportedOperationException) {
            return
          }
      if (PosixFilePermission.OTHERS_READ in permissions) {
        log.warn("The keystore file is readable by other users; restrict it to the service account")
      }
    }
  }
}
