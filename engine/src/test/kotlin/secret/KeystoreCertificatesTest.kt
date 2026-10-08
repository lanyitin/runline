package dev.lawlan.runline.engine.secret

import dev.lawlan.runline.accessors.tls.TestPki
import dev.lawlan.runline.engine.support.Keystores
import dev.lawlan.runline.engine.support.Keytool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * The certificate entries of a real PKCS12 keystore (WI-52): what may be shown of them (subject,
 * end of validity, SHA-256 fingerprint), and the certificates and client keys the Engine uses for a
 * resource's TLS, each under an alias of the right kind only.
 */
class KeystoreCertificatesTest {
  private val keystores = Keystores()
  private val passwordFile = keystores.passwordFile()
  private val pki = TestPki()
  private val authority = pki.authority("Internal CA")
  private val client = pki.issue(authority, "runline-client")

  private fun load(file: java.nio.file.Path) =
      KeystoreLoader(file, SecretValue(Keystores.DEFAULT_PASSWORD)).load()

  /** What `keytool -list` says the SHA-256 fingerprint of [alias] is: what an operator compares. */
  private fun keytoolFingerprint(file: java.nio.file.Path, alias: String): String =
      Regex("SHA-256\\): ([0-9A-F:]+)")
          .find(
              Keytool.run(
                  "-list",
                  "-alias",
                  alias,
                  "-keystore",
                  "$file",
                  "-storepass:file",
                  "$passwordFile",
              )
          )!!
          .groupValues[1]

  @Test
  fun `a trusted certificate is listed with its subject, end of validity and fingerprint`() {
    val file = keystores.pkcs12("certs.p12", mapOf("db-pass" to "value"))
    pki.addTrusted(file, "Internal-CA", authority, passwordFile)

    val entries = load(file).entries().associateBy { it.alias }

    assertEquals(
        listOf(
            CertificateInfo(
                "CN=Internal CA",
                authority.certificate.notAfter.toInstant(),
                keytoolFingerprint(file, "internal-ca"),
            )
        ),
        entries.getValue("internal-ca").certificates,
    )
    assertEquals(emptyList(), entries.getValue("db-pass").certificates)
  }

  @Test
  fun `a private key entry is listed with every certificate of its chain, its own first`() {
    val file = keystores.pkcs12("chain.p12")
    pki.addPrivateKey(file, "app-client", client, passwordFile)

    val listed = load(file).entries().single { it.alias == "app-client" }

    assertEquals(EntryKind.PRIVATE_KEY, listed.kind)
    assertEquals(
        listOf("CN=runline-client", "CN=Internal CA"),
        listed.certificates.map { it.subject },
    )
    assertEquals(keytoolFingerprint(file, "app-client"), listed.certificates.first().fingerprint)
  }

  @Test
  fun `each certificate and client key is found under an alias of its own kind only`() {
    val file = keystores.pkcs12("kinds.p12", mapOf("db-pass" to "value"))
    pki.addTrusted(file, "internal-ca", authority, passwordFile)
    pki.addPrivateKey(file, "app-client", client, passwordFile)

    val snapshot = load(file)

    assertEquals(
        authority.certificate,
        assertIs<EntryLookup.Found<*>>(snapshot.trustedCertificate("Internal-CA")).value,
    )
    val found = assertIs<EntryLookup.Found<*>>(snapshot.clientCertificate("app-client")).value
    assertEquals(client.chain, (found as dev.lawlan.runline.accessors.tls.ClientCertificate).chain)
    assertSame(EntryLookup.WrongType, snapshot.trustedCertificate("app-client"))
    assertSame(EntryLookup.WrongType, snapshot.trustedCertificate("db-pass"))
    assertSame(EntryLookup.WrongType, snapshot.clientCertificate("internal-ca"))
    assertSame(EntryLookup.Missing, snapshot.clientCertificate("nothing"))
    assertSame(SecretLookup.WrongType, snapshot.lookup("internal-ca"))
    assertSame(SecretLookup.Missing, snapshot.lookup("nothing"))
  }

  @Test
  fun `a private key under a password other than the keystore's cannot be used and is said so`() {
    // keytool cannot write such an entry (it ignores -keypass for PKCS12), the JDK's API can.
    val file = keystores.dir.resolve("other-key-password.p12")
    val store = java.security.KeyStore.getInstance("PKCS12").apply { load(null, null) }
    store.setEntry(
        "odd-key",
        java.security.KeyStore.PrivateKeyEntry(client.key, client.chain.toTypedArray()),
        java.security.KeyStore.PasswordProtection("another-password".toCharArray()),
    )
    java.nio.file.Files.newOutputStream(file).use {
      store.store(it, Keystores.DEFAULT_PASSWORD.toCharArray())
    }

    val lines =
        dev.lawlan.runline.engine.support.CapturedLogs().use { logs ->
          val snapshot = load(file)
          val listed = snapshot.entries().single()
          assertEquals(
              EntryKind.PRIVATE_KEY to AliasStatus.INVALID_KEY,
              listed.kind to listed.status,
          )
          assertSame(EntryLookup.Invalid, snapshot.clientCertificate("odd-key"))
          logs.lines
        }
    val line = lines.single { it.contains("odd-key") }
    kotlin.test.assertTrue(line.startsWith("WARN") && line.contains("invalid_key"), line)
  }
}
