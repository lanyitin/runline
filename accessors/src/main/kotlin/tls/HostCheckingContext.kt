package dev.lawlan.runline.accessors.tls

import java.net.InetAddress
import java.net.Socket
import java.security.SecureRandom
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLContextSpi
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.SSLSessionContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager

/**
 * A security context that makes the JDK check the host of every connection made with it (endpoint
 * identification `HTTPS`), whoever the client is. The trust manager of a resource's context refuses
 * a connection that does not ask for this check (ADR-019 decision 12: no client can leave the host
 * unchecked), and a client library that checks the host itself, after the handshake, does not ask
 * the JDK to. Giving such a client this context makes it ask. The context is a view of [delegate]:
 * the same trust, the same client certificate, the same sessions.
 */
class HostCheckingContext private constructor(delegate: SSLContext) :
    SSLContext(Spi(delegate), delegate.provider, delegate.protocol) {
  companion object {
    fun of(delegate: SSLContext): SSLContext = HostCheckingContext(delegate)

    private fun <T : SSLSocket> T.checkingHost(): T = apply {
      sslParameters = sslParameters.also { it.endpointIdentificationAlgorithm = "HTTPS" }
    }

    private fun SSLEngine.checkingHost(): SSLEngine = apply {
      sslParameters = sslParameters.also { it.endpointIdentificationAlgorithm = "HTTPS" }
    }
  }

  private class Spi(private val delegate: SSLContext) : SSLContextSpi() {
    override fun engineInit(
        keyManagers: Array<KeyManager>?,
        trustManagers: Array<TrustManager>?,
        random: SecureRandom?,
    ) = throw UnsupportedOperationException("the context is initialised by its resource")

    override fun engineGetSocketFactory(): SSLSocketFactory = Sockets(delegate.socketFactory)

    override fun engineGetServerSocketFactory(): SSLServerSocketFactory =
        delegate.serverSocketFactory

    override fun engineCreateSSLEngine(): SSLEngine = delegate.createSSLEngine().checkingHost()

    override fun engineCreateSSLEngine(host: String?, port: Int): SSLEngine =
        delegate.createSSLEngine(host, port).checkingHost()

    override fun engineGetServerSessionContext(): SSLSessionContext = delegate.serverSessionContext

    override fun engineGetClientSessionContext(): SSLSessionContext = delegate.clientSessionContext
  }

  private class Sockets(private val delegate: SSLSocketFactory) : SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(): Socket = checked(delegate.createSocket())

    override fun createSocket(
        socket: Socket,
        host: String?,
        port: Int,
        autoClose: Boolean,
    ): Socket = checked(delegate.createSocket(socket, host, port, autoClose))

    override fun createSocket(host: String?, port: Int): Socket =
        checked(delegate.createSocket(host, port))

    override fun createSocket(
        host: String?,
        port: Int,
        local: InetAddress?,
        localPort: Int,
    ): Socket = checked(delegate.createSocket(host, port, local, localPort))

    override fun createSocket(host: InetAddress?, port: Int): Socket =
        checked(delegate.createSocket(host, port))

    override fun createSocket(
        host: InetAddress?,
        port: Int,
        local: InetAddress?,
        localPort: Int,
    ): Socket = checked(delegate.createSocket(host, port, local, localPort))

    private fun checked(socket: Socket): Socket = (socket as SSLSocket).checkingHost()
  }
}
