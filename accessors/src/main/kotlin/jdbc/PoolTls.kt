package dev.lawlan.runline.accessors.jdbc

import dev.lawlan.runline.accessors.tls.ResourceTls
import dev.lawlan.runline.accessors.tls.TlsContext
import dev.lawlan.runline.accessors.tls.TlsFailure
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.sql.SQLException
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * The TLS context of one pool (or one check) of a `jdbc-pool` resource (WI-52), known to the driver
 * only by [id]: a driver such as PostgreSQL's takes the name of a socket factory class and not a
 * context, so its factory ([PostgresTlsSocketFactory]) finds the context here by the id the profile
 * puts in the connection's properties. The context, its key included, lives in memory only; nothing
 * of it is written to a file. [close] forgets it.
 */
internal class PoolTls(tls: ResourceTls, private val profile: JdbcProfile) : AutoCloseable {
  val context: TlsContext = tls.newContext()
  val id: String = UUID.randomUUID().toString()

  init {
    contexts[id] = context
  }

  /**
   * The category of a failure of TLS among the causes of [failure]: what the context tells, or,
   * when the database asked for a client certificate and then refused the account in the way its
   * profile says means "no acceptable certificate", the refusal of the client certificate.
   */
  fun classify(failure: Throwable): TlsFailure? =
      context.classify(failure)
          ?: TlsFailure.CLIENT_CERT_REJECTED.takeIf {
            context.clientCertificateAsked &&
                failure is SQLException &&
                profile.refusesClientCertificate(failure)
          }

  override fun close() {
    contexts.remove(id)
  }

  companion object {
    private val contexts = ConcurrentHashMap<String, TlsContext>()

    /** The context registered under [id]; null when there is none (any more). */
    fun find(id: String?): TlsContext? = id?.let { contexts[it] }
  }
}

/**
 * The socket factory the PostgreSQL profile names for a connection with certificates (WI-52): the
 * driver makes one per connection with the connection's properties, and it layers TLS of the pool's
 * own context over the driver's socket, with the host checked by the JDK during the handshake (the
 * driver checks it again after, as `sslmode=verify-full` says). It is not a property an
 * administrator can set: the profile fixes it, and the allowed properties exclude everything of
 * TLS.
 */
class PostgresTlsSocketFactory(properties: Properties) : SSLSocketFactory() {
  private val factory: SSLSocketFactory =
      checkNotNull(PoolTls.find(properties.getProperty(PostgresProfile.TLS_CONTEXT_PROPERTY))) {
            "no TLS context for this connection"
          }
          .sslContext
          .socketFactory

  override fun createSocket(socket: Socket, host: String, port: Int, autoClose: Boolean): Socket {
    val layered = factory.createSocket(socket, host, port, autoClose) as SSLSocket
    layered.sslParameters =
        layered.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
    return layered
  }

  override fun getDefaultCipherSuites(): Array<String> = factory.defaultCipherSuites

  override fun getSupportedCipherSuites(): Array<String> = factory.supportedCipherSuites

  // The driver only ever layers TLS over the connection it made; a new connection from here would
  // bypass its connect limits.
  override fun createSocket(host: String, port: Int): Socket = throw IOException("not supported")

  override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
      throw IOException("not supported")

  override fun createSocket(host: InetAddress, port: Int): Socket =
      throw IOException("not supported")

  override fun createSocket(
      address: InetAddress,
      port: Int,
      local: InetAddress,
      localPort: Int,
  ): Socket = throw IOException("not supported")
}
