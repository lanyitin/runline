package dev.lawlan.runline.accessors.tls

import java.net.Socket
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.X509Certificate
import java.util.UUID
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLException
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/** Why a TLS connection of a resource failed, as a category (WI-52); [wire] names it in the API. */
enum class TlsFailure(val wire: String) {
  /** The service's certificate does not lead to a certificate the resource trusts. */
  TRUST_FAILED("trust_failed"),

  /** The service's certificate, or one of its chain, is past its end of validity. */
  CERTIFICATE_EXPIRED("certificate_expired"),

  /** The service's certificate is not for the host the resource connects to. */
  HOSTNAME_MISMATCH("hostname_mismatch"),

  /** The service asked for a client certificate and refused the connection. */
  CLIENT_CERT_REJECTED("client_cert_rejected"),

  /** The handshake failed for another reason (no common protocol, a broken connection). */
  HANDSHAKE_FAILED("handshake_failed"),
}

/** A private key with its certificate chain (its own certificate first), held in memory only. */
class ClientCertificate(internal val key: PrivateKey, val chain: List<X509Certificate>) {
  override fun toString() = "ClientCertificate(${chain.firstOrNull()?.subjectX500Principal})"
}

/**
 * The TLS of a resource's connections (ADR-019 decision 12): the certificates it trusts, which
 * replace the JVM's default trust when there are any, and the client certificate it presents when a
 * service asks for one.
 */
class ResourceTls(val trusted: List<X509Certificate>, val client: ClientCertificate?) {
  /** A context of its own for connections of the resource. */
  fun newContext(): TlsContext {
    val anchors =
        if (trusted.isEmpty()) null
        else
            KeyStore.getInstance("PKCS12").apply {
              load(null, null)
              trusted.forEachIndexed { i, it -> setCertificateEntry("trusted-$i", it) }
            }
    val trust = TrustManagerFactory.getInstance("PKIX").apply { init(anchors) }
    val delegate = trust.trustManagers.filterIsInstance<X509ExtendedTrustManager>().single()
    val keys = PresentingKeyManager(client?.let(::keyManagerOf))
    val context =
        SSLContext.getInstance("TLS").apply {
          init(arrayOf(keys), arrayOf(VerifyingTrustManager(delegate)), null)
        }
    return TlsContext(context, keys)
  }

  /**
   * The JDK's own key manager over the client certificate, in a keystore that exists only in memory
   * for as long as the context does: the key is never written anywhere. The password of that
   * keystore is made up here and is nobody's.
   */
  private fun keyManagerOf(client: ClientCertificate): X509ExtendedKeyManager {
    val password = UUID.randomUUID().toString().toCharArray()
    val store = KeyStore.getInstance("PKCS12").apply { load(null, null) }
    store.setKeyEntry("client", client.key, password, client.chain.toTypedArray())
    val factory = KeyManagerFactory.getInstance("PKIX").apply { init(store, password) }
    return factory.keyManagers.filterIsInstance<X509ExtendedKeyManager>().single()
  }

  override fun toString() = "ResourceTls(${trusted.size} trusted, client=${client != null})"
}

/** The security context of some connections of a resource, and how their failures are told. */
class TlsContext
internal constructor(val sslContext: SSLContext, private val keys: PresentingKeyManager) {
  /**
   * The category of a failure of a connection made with [sslContext]; null when it is not of TLS.
   * What the resource refused is told by its own check; a failure of the handshake otherwise is the
   * service's refusal of the client certificate when the service asked for one on a connection of
   * this context, and a plain handshake failure when not. Nothing depends on the words of a
   * message.
   */
  fun classify(failure: Throwable): TlsFailure? {
    val causes = generateSequence(failure) { it.cause }.toList()
    causes
        .firstNotNullOfOrNull { (it as? TlsRejection)?.failure }
        ?.let {
          return it
        }
    if (causes.none { it is SSLException }) return null
    return if (keys.asked) TlsFailure.CLIENT_CERT_REJECTED else TlsFailure.HANDSHAKE_FAILED
  }

  /** Whether a service asked a connection of this context for a client certificate. */
  val clientCertificateAsked: Boolean
    get() = keys.asked
}

/**
 * Gives a service that asks for a client certificate the resource's, when it has one, by the JDK's
 * own choice; and notes that it was asked, which is what tells a refusal of the certificate from
 * another failure of the handshake.
 */
internal class PresentingKeyManager(private val delegate: X509ExtendedKeyManager?) :
    X509ExtendedKeyManager() {
  @Volatile
  var asked = false
    private set

  override fun chooseClientAlias(
      keyType: Array<String>?,
      issuers: Array<Principal>?,
      socket: Socket?,
  ): String? {
    asked = true
    return delegate?.chooseClientAlias(keyType, issuers, socket)
  }

  override fun chooseEngineClientAlias(
      keyType: Array<String>?,
      issuers: Array<Principal>?,
      engine: SSLEngine?,
  ): String? {
    asked = true
    return delegate?.chooseEngineClientAlias(keyType, issuers, engine)
  }

  override fun getClientAliases(keyType: String?, issuers: Array<Principal>?): Array<String>? =
      delegate?.getClientAliases(keyType, issuers)

  override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
      delegate?.getCertificateChain(alias)

  override fun getPrivateKey(alias: String?): PrivateKey? = delegate?.getPrivateKey(alias)

  override fun getServerAliases(keyType: String?, issuers: Array<Principal>?): Array<String>? = null

  override fun chooseServerAlias(
      keyType: String?,
      issuers: Array<Principal>?,
      socket: Socket?,
  ): String? = null
}

/** A certificate a resource does not accept, with the category of why; never more than that. */
internal class TlsRejection(val failure: TlsFailure, cause: Throwable?) :
    CertificateException(failure.wire, cause)

/**
 * The JDK's own checks of a service's certificate, made in an order that tells what failed: the
 * chain first, which is the trust; nothing is decided here that the JDK does not decide.
 */
internal class VerifyingTrustManager(private val delegate: X509ExtendedTrustManager) :
    X509ExtendedTrustManager() {
  override fun checkServerTrusted(
      chain: Array<X509Certificate>,
      authType: String,
      socket: Socket?,
  ) {
    verifyChain(chain, authType)
    val ssl = socket as? SSLSocket
    verifyHost(ssl?.sslParameters) { delegate.checkServerTrusted(chain, authType, ssl) }
  }

  override fun checkServerTrusted(
      chain: Array<X509Certificate>,
      authType: String,
      engine: SSLEngine?,
  ) {
    verifyChain(chain, authType)
    verifyHost(engine?.sslParameters) { delegate.checkServerTrusted(chain, authType, engine) }
  }

  /** Without a connection there is no host to check the certificate against: refused. */
  override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
    verifyChain(chain, authType)
    throw TlsRejection(TlsFailure.HOSTNAME_MISMATCH, null)
  }

  /** The chain on its own: whether it leads to a trusted certificate, as the JDK decides. */
  private fun verifyChain(chain: Array<X509Certificate>, authType: String) {
    try {
      delegate.checkServerTrusted(chain, authType)
    } catch (e: CertificateException) {
      throw TlsRejection(
          if (expired(e)) TlsFailure.CERTIFICATE_EXPIRED else TlsFailure.TRUST_FAILED,
          e,
      )
    }
  }

  /**
   * Whether the chain failed for a certificate past its validity: by the types and the reason the
   * JDK's path validation gives (JDK 25: a `CertPathValidatorException` of reason `EXPIRED`, or a
   * `CertificateExpiredException`), never by the words of a message.
   */
  private fun expired(e: Throwable): Boolean =
      generateSequence(e) { it.cause }
          .any {
            it is CertificateExpiredException ||
                (it is CertPathValidatorException &&
                    it.reason == CertPathValidatorException.BasicReason.EXPIRED)
          }

  /**
   * The JDK's check of the whole connection, the host included, after the chain passed: what fails
   * now is the host (or a constraint of the connection, which is told by its cause). A connection
   * that does not ask the JDK to check the host (no identification algorithm: something switched it
   * off) is refused, so that no setting anywhere can make a resource skip it.
   */
  private fun verifyHost(parameters: SSLParameters?, check: () -> Unit) {
    if (parameters?.endpointIdentificationAlgorithm.isNullOrEmpty()) {
      throw TlsRejection(TlsFailure.HOSTNAME_MISMATCH, null)
    }
    try {
      check()
    } catch (e: CertificateException) {
      val ofTheChain =
          generateSequence(e.cause) { it.cause }.any { it is CertPathValidatorException }
      throw TlsRejection(
          if (ofTheChain) TlsFailure.TRUST_FAILED else TlsFailure.HOSTNAME_MISMATCH,
          e,
      )
    }
  }

  override fun checkClientTrusted(
      chain: Array<X509Certificate>,
      authType: String,
      socket: Socket?,
  ) = throw CertificateException("a resource's connections are clients only")

  override fun checkClientTrusted(
      chain: Array<X509Certificate>,
      authType: String,
      engine: SSLEngine?,
  ) = throw CertificateException("a resource's connections are clients only")

  override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
      throw CertificateException("a resource's connections are clients only")

  override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
}
