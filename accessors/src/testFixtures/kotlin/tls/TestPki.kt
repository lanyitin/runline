package dev.lawlan.runline.accessors.tls

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.io.path.readBytes
import kotlin.io.path.writeText

/**
 * Certificates for tests of TLS (WI-52), made by the real `keytool` of the JDK the tests run on,
 * the tool operators use: certificate authorities, certificates they issue (with the names, the
 * validity and the dates a test asks for) and self-signed ones. Nothing here is a stand-in: every
 * certificate is a real X.509 certificate a real handshake checks.
 */
class TestPki(val dir: Path = Files.createTempDirectory("pki")) {
  private val passwordFile: Path = dir.resolve("pki.pw").also { it.writeText("$PASSWORD\n") }

  /** A private key with its chain, the first certificate being its own, and the file it is in. */
  class Issued(
      val file: Path,
      val alias: String,
      val key: PrivateKey,
      val chain: List<X509Certificate>,
  ) {
    val certificate: X509Certificate
      get() = chain.first()

    override fun toString() = "Issued($alias)"
  }

  /** A certificate authority: one that may sign others. */
  fun authority(name: String): Issued {
    val file = dir.resolve("$name.${UUID.randomUUID()}.p12")
    genkeypair(file, name, "CN=$name", "-ext", "bc:c")
    return read(file, name)
  }

  /**
   * A certificate for [cn] signed by [issuer], valid for [days] from [startDate] (keytool's form,
   * e.g. `-400d`; now when null), with the subject alternative names [san] (e.g. `dns:localhost`).
   */
  fun issue(
      issuer: Issued,
      cn: String,
      san: List<String> = emptyList(),
      days: Int = 365,
      startDate: String? = null,
  ): Issued {
    val alias = cn.lowercase().replace(Regex("[^a-z0-9]"), "-")
    val file = dir.resolve("$alias.${UUID.randomUUID()}.p12")
    genkeypair(file, alias, "CN=$cn")
    val request = dir.resolve("$alias.${UUID.randomUUID()}.csr")
    keytool("-certreq", "-alias", alias, "-keystore", "$file", "-file", "$request")
    val signed = dir.resolve("$alias.${UUID.randomUUID()}.pem")
    keytool(
        "-gencert",
        "-alias",
        issuer.alias,
        "-keystore",
        "${issuer.file}",
        "-infile",
        "$request",
        "-outfile",
        "$signed",
        "-rfc",
        "-validity",
        "$days",
        *(if (startDate != null) arrayOf("-startdate", startDate) else emptyArray()),
        *(if (san.isNotEmpty()) arrayOf("-ext", "SAN=" + san.joinToString(",")) else emptyArray()),
    )
    // The issuer's certificate first, then the reply: the entry gets its whole chain.
    val issuerPem = export(issuer)
    keytool(
        "-importcert",
        "-noprompt",
        "-alias",
        "issuer",
        "-file",
        "$issuerPem",
        "-keystore",
        "$file",
    )
    keytool("-importcert", "-noprompt", "-alias", alias, "-file", "$signed", "-keystore", "$file")
    keytool("-delete", "-alias", "issuer", "-keystore", "$file")
    return read(file, alias)
  }

  /** A certificate for [cn] that signs itself, with the names [san]. */
  fun selfSigned(cn: String, san: List<String> = emptyList()): Issued {
    val alias = cn.lowercase().replace(Regex("[^a-z0-9]"), "-")
    val file = dir.resolve("$alias.${UUID.randomUUID()}.p12")
    genkeypair(
        file,
        alias,
        "CN=$cn",
        *(if (san.isNotEmpty()) arrayOf("-ext", "SAN=" + san.joinToString(",")) else emptyArray()),
    )
    return read(file, alias)
  }

  /** The certificate of [issued] as a PEM file. */
  fun export(issued: Issued): Path {
    val pem = dir.resolve("${issued.alias}.${UUID.randomUUID()}.cer")
    keytool(
        "-exportcert",
        "-rfc",
        "-alias",
        issued.alias,
        "-keystore",
        "${issued.file}",
        "-file",
        "$pem",
    )
    return pem
  }

  /**
   * The private key of [issued] as a PEM file, for a server under test that reads its own key from
   * a file (PostgreSQL). Only ever a server's key of a test; never anything the Engine holds.
   */
  fun serverKeyPem(issued: Issued): Path {
    val pem = dir.resolve("${issued.alias}.${UUID.randomUUID()}.key")
    pem.writeText(
        "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(issued.key.encoded) +
            "\n-----END PRIVATE KEY-----\n"
    )
    return pem
  }

  /** The certificates of [issued] as a PEM file, its own first. */
  fun chainPem(issued: Issued): Path {
    val pem = dir.resolve("${issued.alias}.${UUID.randomUUID()}.chain.pem")
    pem.writeText(
        issued.chain.joinToString("") {
          "-----BEGIN CERTIFICATE-----\n" +
              Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(it.encoded) +
              "\n-----END CERTIFICATE-----\n"
        }
    )
    return pem
  }

  /**
   * The TLS of a server that presents [identity] and, when [clientsOf] is not null, asks for a
   * client certificate (and requires one when [requireClient]) issued by one of [clientsOf].
   */
  fun serverContext(
      identity: Issued,
      clientsOf: List<X509Certificate>? = null,
  ): SSLContext {
    val keys = KeyStore.getInstance("PKCS12").apply { load(null, null) }
    keys.setKeyEntry("server", identity.key, PASSWORD.toCharArray(), identity.chain.toTypedArray())
    val keyManagers =
        KeyManagerFactory.getInstance("PKIX").apply { init(keys, PASSWORD.toCharArray()) }
    val trust = KeyStore.getInstance("PKCS12").apply { load(null, null) }
    clientsOf.orEmpty().forEachIndexed { i, it -> trust.setCertificateEntry("client-ca-$i", it) }
    val trustManagers =
        if (clientsOf == null) null
        else TrustManagerFactory.getInstance("PKIX").apply { init(trust) }.trustManagers
    return SSLContext.getInstance("TLS").apply {
      init(keyManagers.keyManagers, trustManagers, null)
    }
  }

  /**
   * Adds [issued] to the PKCS12 keystore [keystore] (made if it does not exist) as a private key
   * entry under [alias], with its whole chain; [storePasswordFile] opens the keystore.
   */
  fun addPrivateKey(keystore: Path, alias: String, issued: Issued, storePasswordFile: Path) {
    keytool(
        "-importkeystore",
        "-noprompt",
        "-srckeystore",
        "${issued.file}",
        "-srcstoretype",
        "pkcs12",
        "-srcstorepass:file",
        "$passwordFile",
        "-srcalias",
        issued.alias,
        "-destkeystore",
        "$keystore",
        "-deststoretype",
        "pkcs12",
        "-deststorepass:file",
        "$storePasswordFile",
        "-destalias",
        alias,
        withStorePassword = false,
    )
  }

  /** Adds the certificate of [issued] to [keystore] as a trusted certificate entry [alias]. */
  fun addTrusted(keystore: Path, alias: String, issued: Issued, storePasswordFile: Path) {
    keytool(
        "-importcert",
        "-noprompt",
        "-alias",
        alias,
        "-file",
        "${export(issued)}",
        "-keystore",
        "$keystore",
        "-storetype",
        "pkcs12",
        "-storepass:file",
        "$storePasswordFile",
        withStorePassword = false,
    )
  }

  private fun genkeypair(file: Path, alias: String, dname: String, vararg extra: String) {
    keytool(
        "-genkeypair",
        "-alias",
        alias,
        "-keyalg",
        "EC",
        "-groupname",
        "secp256r1",
        "-dname",
        dname,
        "-validity",
        "365",
        "-keystore",
        "$file",
        *extra,
    )
  }

  private fun read(file: Path, alias: String): Issued {
    val store = KeyStore.getInstance("PKCS12")
    ByteArrayInputStream(file.readBytes()).use { store.load(it, PASSWORD.toCharArray()) }
    val factory = CertificateFactory.getInstance("X.509")
    val chain =
        store.getCertificateChain(alias).map {
          factory.generateCertificate(ByteArrayInputStream(it.encoded)) as X509Certificate
        }
    return Issued(file, alias, store.getKey(alias, PASSWORD.toCharArray()) as PrivateKey, chain)
  }

  private fun keytool(vararg args: String, withStorePassword: Boolean = true) {
    val all =
        listOf(Path.of(System.getProperty("java.home"), "bin", "keytool").toString()) +
            args +
            (if (withStorePassword)
                listOf("-storetype", "pkcs12", "-storepass:file", "$passwordFile")
            else emptyList())
    val process = ProcessBuilder(all).redirectErrorStream(true).start()
    process.outputStream.close()
    val output = process.inputStream.readAllBytes().decodeToString()
    check(process.waitFor(60, TimeUnit.SECONDS)) { "keytool did not end: $args" }
    check(process.exitValue() == 0) { "keytool ${args.toList()} failed: $output" }
  }

  companion object {
    /** The password of every file of the PKI itself (not of a keystore a test gives the Engine). */
    const val PASSWORD = "pki-pass-1"
  }
}
