package dev.lawlan.runline.engine.secret

import dev.lawlan.runline.accessors.tls.ClientCertificate
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.UnrecoverableKeyException
import java.security.cert.X509Certificate
import java.util.HexFormat
import javax.crypto.SecretKey
import org.slf4j.LoggerFactory

/** What one reading of the keystore found: the entries, by normalised alias. */
class KeystoreSnapshot(private val entries: Map<String, LoadedEntry>) {
  /** One entry as read. */
  class LoadedEntry(
      val kind: EntryKind,
      val status: AliasStatus,
      val value: SecretValue?,
      /** Tells whether the entry changed, without keeping or showing what it holds. */
      val fingerprint: String,
      /**
       * The certificate of a certificate entry, the chain of a private key entry, its own first.
       */
      val certificates: List<X509Certificate> = emptyList(),
      /** The key of a private key entry, in memory only (WI-52). */
      val key: PrivateKey? = null,
  )

  fun entries(): List<SecretEntryInfo> =
      entries.toSortedMap().map { (alias, entry) ->
        SecretEntryInfo(alias, entry.kind, entry.status, entry.certificates.map(::infoOf))
      }

  /** The trusted certificate under [alias]; an entry of another kind is [EntryLookup.WrongType]. */
  fun trustedCertificate(alias: String): EntryLookup<X509Certificate> {
    val entry = entries[normalizeAlias(alias)] ?: return EntryLookup.Missing
    if (entry.kind != EntryKind.TRUSTED_CERTIFICATE) return EntryLookup.WrongType
    return EntryLookup.Found(entry.certificates.single())
  }

  /** The private key and chain under [alias], for a client certificate. */
  fun clientCertificate(alias: String): EntryLookup<ClientCertificate> {
    val entry = entries[normalizeAlias(alias)] ?: return EntryLookup.Missing
    if (entry.kind != EntryKind.PRIVATE_KEY) return EntryLookup.WrongType
    val key = entry.key ?: return EntryLookup.Invalid
    return EntryLookup.Found(ClientCertificate(key, entry.certificates))
  }

  fun lookup(alias: String): SecretLookup {
    val entry = entries[normalizeAlias(alias)]
    return when {
      entry == null -> SecretLookup.Missing
      entry.kind != EntryKind.SECRET -> SecretLookup.WrongType
      entry.value == null -> SecretLookup.Invalid
      else -> SecretLookup.Found(entry.value)
    }
  }

  /** The fingerprint of every alias. */
  fun fingerprints(): Map<String, String> = entries.mapValues { it.value.fingerprint }

  /** The values of the secrets that can be used. */
  fun secretValues(): List<String> = entries.values.mapNotNull { it.value?.reveal() }
}

/**
 * What may be shown of [certificate]: its subject, its end of validity, and its SHA-256 fingerprint
 * as `keytool -list` prints it (upper case hexadecimal pairs separated by colons), so that an
 * operator can compare it with what the tool says.
 */
internal fun infoOf(certificate: X509Certificate): CertificateInfo =
    CertificateInfo(
        certificate.subjectX500Principal.name,
        certificate.notAfter.toInstant(),
        HexFormat.ofDelimiter(":")
            .withUpperCase()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.encoded)),
    )

/** Reads a PKCS12 keystore, and only that, read-only (ADR-019 decision 6, WI-41). */
class KeystoreLoader(private val path: Path, private val password: SecretValue) {
  private val log = LoggerFactory.getLogger(KeystoreLoader::class.java)

  /** Throws [KeystoreOpenException] with the category when the file cannot be opened. */
  fun load(): KeystoreSnapshot {
    val chars = password.reveal().toCharArray()
    val store = open(chars)
    val entries = buildMap {
      for (alias in store.aliases()) {
        val entry = entryOf(store, alias, chars)
        if (entry == null) {
          // Said by alias and type only; there is no value to say.
          log.warn("Keystore alias {} is of a type that is not used and is ignored", alias)
        } else {
          put(normalizeAlias(alias), entry)
        }
      }
    }
    return KeystoreSnapshot(entries)
  }

  /** The entry under [alias], or null when it is of none of the three kinds the Engine uses. */
  private fun entryOf(
      store: KeyStore,
      alias: String,
      chars: CharArray,
  ): KeystoreSnapshot.LoadedEntry? =
      when {
        store.entryInstanceOf(alias, KeyStore.SecretKeyEntry::class.java) ->
            secretEntry(alias, (store.getKey(alias, chars) as SecretKey).encoded)
        store.entryInstanceOf(alias, KeyStore.PrivateKeyEntry::class.java) -> {
          val chain = store.getCertificateChain(alias).map { it as X509Certificate }
          val key = keyOf(store, alias, chars)
          KeystoreSnapshot.LoadedEntry(
              EntryKind.PRIVATE_KEY,
              if (key == null) AliasStatus.INVALID_KEY else AliasStatus.FOUND,
              null,
              // The chain says the entry changed; the private key itself is not in it.
              fingerprintOf("private_key", *chain.map { it.encoded }.toTypedArray()),
              chain,
              key,
          )
        }
        store.entryInstanceOf(alias, KeyStore.TrustedCertificateEntry::class.java) -> {
          val certificate = store.getCertificate(alias) as X509Certificate
          KeystoreSnapshot.LoadedEntry(
              EntryKind.TRUSTED_CERTIFICATE,
              AliasStatus.FOUND,
              null,
              fingerprintOf("trusted_certificate", certificate.encoded),
              listOf(certificate),
          )
        }
        else -> null
      }

  /**
   * The private key under [alias], opened with the keystore's own password, which is what protects
   * a key of a PKCS12 file written by keytool (ADR-019 decision 12; WI-52 measured it). A key that
   * another tool protected otherwise is null: it is said by alias and category and never used.
   */
  private fun keyOf(store: KeyStore, alias: String, chars: CharArray): PrivateKey? =
      try {
        store.getKey(alias, chars) as PrivateKey
      } catch (e: UnrecoverableKeyException) {
        log.warn("Keystore alias {} is {} and is not used", alias, AliasStatus.INVALID_KEY.wire)
        null
      }

  private fun secretEntry(alias: String, bytes: ByteArray): KeystoreSnapshot.LoadedEntry =
      if (isPrintableAscii(bytes)) {
        KeystoreSnapshot.LoadedEntry(
            EntryKind.SECRET,
            AliasStatus.FOUND,
            SecretValue(String(bytes, Charsets.US_ASCII)),
            fingerprintOf("secret", bytes),
        )
      } else {
        // Only the alias and the category are said, never what the value looks like.
        log.warn("Keystore alias {} is {} and is not used", alias, AliasStatus.INVALID_SECRET.wire)
        KeystoreSnapshot.LoadedEntry(
            EntryKind.SECRET,
            AliasStatus.INVALID_SECRET,
            null,
            fingerprintOf("invalid_secret", bytes),
        )
      }

  /**
   * Opens the file as PKCS12 only. The format is checked here and not left to the JDK: with its
   * default `keystore.type.compat`, a request for PKCS12 also opens a JKS file. What an operator
   * did wrong is told apart as far as the JDK's exceptions allow; what cannot be told is
   * [OpenFailure.UNREADABLE]. A wrong password is an [IOException] caused by an
   * [UnrecoverableKeyException] (JDK 25; revalidate with every JDK upgrade).
   */
  private fun open(chars: CharArray): KeyStore {
    val store = KeyStore.getInstance("PKCS12")
    try {
      BufferedInputStream(Files.newInputStream(path)).use { input ->
        requirePkcs12(input)
        store.load(input, chars)
      }
    } catch (e: KeystoreOpenException) {
      throw e
    } catch (e: NoSuchFileException) {
      throw KeystoreOpenException(OpenFailure.FILE_MISSING)
    } catch (e: EOFException) {
      throw KeystoreOpenException(OpenFailure.CORRUPT)
    } catch (e: IOException) {
      throw KeystoreOpenException(
          if (e.cause is UnrecoverableKeyException) OpenFailure.WRONG_PASSWORD
          else OpenFailure.UNREADABLE
      )
    } catch (e: GeneralSecurityException) {
      throw KeystoreOpenException(OpenFailure.UNREADABLE)
    }
    return store
  }

  /** A PKCS12 file starts with a DER sequence; JKS and JCEKS start with their own magic. */
  private fun requirePkcs12(input: BufferedInputStream) {
    input.mark(1)
    val first = input.read()
    input.reset()
    when (first) {
      -1 -> throw KeystoreOpenException(OpenFailure.CORRUPT)
      DER_SEQUENCE -> Unit
      else -> throw KeystoreOpenException(OpenFailure.WRONG_FORMAT)
    }
  }

  private fun fingerprintOf(kind: String, vararg parts: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(kind.toByteArray())
    parts.forEach {
      digest.update(it)
      digest.update(0)
    }
    return HexFormat.of().formatHex(digest.digest())
  }

  /** Letters, digits, space and symbols: what the keystore tool can store and give back intact. */
  private fun isPrintableAscii(bytes: ByteArray) =
      bytes.isNotEmpty() && bytes.all { it in 0x20..0x7E }

  private companion object {
    const val DER_SEQUENCE = 0x30
  }
}
