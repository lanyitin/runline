package dev.lawlan.runline.engine.secret

import dev.lawlan.runline.accessors.tls.ClientCertificate
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Locale

/** What an entry of the keystore is (ADR-019 decision 12); only secret entries hold a value. */
enum class EntryKind(val wire: String) {
  SECRET("secret"),
  TRUSTED_CERTIFICATE("trusted_certificate"),
  PRIVATE_KEY("private_key"),
}

/** Whether the entry under an alias can be used; the same words a resource's alias status uses. */
enum class AliasStatus(val wire: String) {
  FOUND("found"),

  /** A secret whose value is not printable ASCII (ADR-019 decision 6): never used, never shown. */
  INVALID_SECRET("invalid_secret"),

  /**
   * A private key the keystore's password does not open (another tool protected it with a password
   * of its own; ADR-019 decision 12 says it is the keystore's): never used (WI-52).
   */
  INVALID_KEY("invalid_key"),
}

/**
 * A certificate of an entry, as it may be shown (WI-52): its subject, the end of its validity and
 * its SHA-256 fingerprint in the form `keytool -list` prints. Never anything of a key.
 */
data class CertificateInfo(val subject: String, val notAfter: Instant, val fingerprint: String)

/**
 * One alias as it is listed: what it is and whether it can be used, never what it holds; for a
 * certificate entry its certificate, for a private key entry the certificates of its chain.
 */
data class SecretEntryInfo(
    val alias: String,
    val kind: EntryKind,
    val status: AliasStatus,
    val certificates: List<CertificateInfo> = emptyList(),
)

/** What looking up a certificate or a client key finds (WI-52). */
sealed interface EntryLookup<out T> {
  class Found<T>(val value: T) : EntryLookup<T> {
    override fun toString() = "Found"
  }

  /** No entry under the alias (also: no keystore at all). */
  data object Missing : EntryLookup<Nothing>

  /** An entry of another kind under the alias. */
  data object WrongType : EntryLookup<Nothing>

  /** The entry is of the kind but cannot be used. */
  data object Invalid : EntryLookup<Nothing>
}

/**
 * The value of a secret. It has no accessor but [reveal], and no text form that shows it, so that a
 * log line, an error message or a response that is handed one by mistake still holds nothing.
 */
class SecretValue(private val text: String) {
  fun reveal(): String = text

  override fun toString() = "SecretValue(***)"
}

/** What looking an alias up finds. */
sealed interface SecretLookup {
  class Found(val value: SecretValue) : SecretLookup {
    override fun toString() = "Found(***)"
  }

  /** No secret entry under the alias (also: no keystore at all). */
  data object Missing : SecretLookup

  /** An entry whose value is not printable ASCII; it is refused. */
  data object Invalid : SecretLookup

  /** An entry of another kind than a secret under the alias (WI-52). */
  data object WrongType : SecretLookup
}

/** The way a keystore could not be opened, as a category and nothing more (WI-41). */
enum class OpenFailure(val wire: String) {
  FILE_MISSING("file_missing"),
  WRONG_PASSWORD("wrong_password"),
  CORRUPT("corrupt"),
  WRONG_FORMAT("wrong_format"),
  UNREADABLE("unreadable"),
}

/** A keystore could not be opened; the message is the category, never a path or a password. */
class KeystoreOpenException(val failure: OpenFailure) :
    RuntimeException("The keystore cannot be opened: ${failure.wire}")

/** What a reload found. */
sealed interface ReloadResult {
  /** [changed]: aliases added, removed or whose content differs, by alias, sorted. */
  data class Reloaded(val aliases: Int, val changed: List<String>) : ReloadResult

  /** The file could not be read; what was in memory is unchanged. */
  data class Failed(val failure: OpenFailure) : ReloadResult

  data object NotConfigured : ReloadResult
}

/**
 * Where secrets come from (ADR-019 decision 6). The only provider so far is the PKCS12 keystore;
 * others can be added without changing what uses this interface (ADR-012's principle).
 */
interface SecretStore {
  /** False when the Engine was given no keystore: nothing is ever found then. */
  val configured: Boolean

  /** Every alias, in order, as normalised by [normalizeAlias]. */
  fun entries(): List<SecretEntryInfo>

  fun lookup(alias: String): SecretLookup

  /** The trusted certificate under [alias] (WI-52). */
  fun trustedCertificate(alias: String): EntryLookup<X509Certificate>

  /** The private key and chain under [alias], for a resource's client certificate (WI-52). */
  fun clientCertificate(alias: String): EntryLookup<ClientCertificate>

  /** Reads the keystore again as a whole; on failure what is in memory stays. */
  fun reload(): ReloadResult
}

/** The one way an alias is compared: the keystore tool writes aliases in lower case. */
fun normalizeAlias(alias: String): String = alias.lowercase(Locale.ROOT)
