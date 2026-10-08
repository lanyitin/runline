package dev.lawlan.runline.accessors.tls

import dev.lawlan.runline.accessors.fake.FakeOpenAiServer
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The TLS of a resource's connections against real TLS servers (the Fake behind TLS, on a real
 * socket) with real certificates made by `keytool` and the JDK's own client (WI-52).
 */
class ResourceTlsTest {
  private val servers = mutableListOf<FakeOpenAiServer>()

  @AfterTest fun stop() = servers.forEach { it.close() }

  private fun server(
      identity: TestPki.Issued,
      clientsOf: List<java.security.cert.X509Certificate>? = null,
      requireClient: Boolean = false,
  ) =
      FakeOpenAiServer(
              tls = pki.serverContext(identity, clientsOf),
              requireClientCertificate = requireClient,
          )
          .also { servers += it }

  /** A GET of the models, with the resource's TLS; the status, or the failure. */
  private fun get(context: TlsContext, url: String): Int {
    val client =
        HttpClient.newBuilder()
            .sslContext(context.sslContext)
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build()
    return client
        .send(
            HttpRequest.newBuilder(URI.create("$url/models"))
                .timeout(Duration.ofSeconds(10))
                .build(),
            HttpResponse.BodyHandlers.discarding(),
        )
        .statusCode()
  }

  /** The category of the failure of a GET that must fail. */
  private fun failure(context: TlsContext, url: String): TlsFailure? {
    val e = assertFailsWith<IOException> { get(context, url) }
    return context.classify(e)
  }

  @Test
  fun `a service whose certificate the trusted authority issued is reached over TLS`() {
    val server = server(serviceIdentity)
    val tls = ResourceTls(listOf(authority.certificate), null)

    assertEquals(200, get(tls.newContext(), server.baseUrl("localhost")))
  }

  @Test
  fun `a service whose certificate another authority issued is refused as trust_failed`() {
    val server = server(pki.issue(otherAuthority, "localhost", listOf("dns:localhost")))
    val tls = ResourceTls(listOf(authority.certificate), null)

    assertEquals(TlsFailure.TRUST_FAILED, failure(tls.newContext(), server.baseUrl("localhost")))
  }

  @Test
  fun `a service reached by a name its certificate is not for is refused as hostname_mismatch`() {
    // The certificate names `localhost` only; the address is the same host by its number.
    val server = server(serviceIdentity)
    val tls = ResourceTls(listOf(authority.certificate), null)

    assertEquals(
        TlsFailure.HOSTNAME_MISMATCH,
        failure(tls.newContext(), server.baseUrl("127.0.0.1")),
    )
  }

  @Test
  fun `a connection that does not ask for the host to be checked is refused, whatever turned it off`() {
    // A socket of the resource's context on which nothing set an identification algorithm: what
    // a client switched off by a system property (the JDK's HTTP client has one) comes to.
    val server = server(serviceIdentity)
    val context = ResourceTls(listOf(authority.certificate), null).newContext()
    val socket =
        context.sslContext.socketFactory.createSocket("localhost", server.port) as SSLSocket

    val e = socket.use { assertFailsWith<SSLHandshakeException> { it.startHandshake() } }

    assertEquals(TlsFailure.HOSTNAME_MISMATCH, context.classify(e))
  }

  @Test
  fun `a service whose certificate has expired is refused as certificate_expired`() {
    val expired =
        pki.issue(authority, "localhost", listOf("dns:localhost"), days = 30, startDate = "-400d")
    val server = server(expired)
    val tls = ResourceTls(listOf(authority.certificate), null)

    assertEquals(
        TlsFailure.CERTIFICATE_EXPIRED,
        failure(tls.newContext(), server.baseUrl("localhost")),
    )
  }

  @Test
  fun `a service that requires a client certificate is reached with the resource's certificate`() {
    val server =
        server(serviceIdentity, clientsOf = listOf(authority.certificate), requireClient = true)
    val tls = ResourceTls(listOf(authority.certificate), clientCertificate)

    assertEquals(200, get(tls.newContext(), server.baseUrl("localhost")))
  }

  @Test
  fun `without a client certificate such a service refuses, as client_cert_rejected`() {
    val server =
        server(serviceIdentity, clientsOf = listOf(authority.certificate), requireClient = true)
    val tls = ResourceTls(listOf(authority.certificate), null)

    assertEquals(
        TlsFailure.CLIENT_CERT_REJECTED,
        failure(tls.newContext(), server.baseUrl("localhost")),
    )
  }

  @Test
  fun `making contexts leaves the JVM's default context, factories and properties as they were`() {
    val defaultContext = javax.net.ssl.SSLContext.getDefault()
    val defaultFactory = javax.net.ssl.HttpsURLConnection.getDefaultSSLSocketFactory()
    val properties = System.getProperties().toMap()

    ResourceTls(listOf(authority.certificate), clientCertificate).newContext()
    ResourceTls(emptyList(), null).newContext()

    kotlin.test.assertSame(defaultContext, javax.net.ssl.SSLContext.getDefault())
    kotlin.test.assertSame(
        defaultFactory,
        javax.net.ssl.HttpsURLConnection.getDefaultSSLSocketFactory(),
    )
    assertEquals(properties, System.getProperties().toMap())
  }

  @Test
  fun `the text of a TLS setting names the certificate and holds nothing of the key`() {
    val key = java.util.Base64.getEncoder().encodeToString(clientIdentity.key.encoded)
    val texts =
        listOf(
            ResourceTls(listOf(authority.certificate), clientCertificate).toString(),
            clientCertificate.toString(),
        )

    texts.forEach {
      kotlin.test.assertFalse(it.contains(key) || it.contains(clientIdentity.key.toString()), it)
    }
  }

  private companion object {
    val pki = TestPki()
    val authority = pki.authority("Internal CA")
    val otherAuthority = pki.authority("Other CA")
    val serviceIdentity = pki.issue(authority, "localhost", listOf("dns:localhost"))
    val clientIdentity = pki.issue(authority, "runline-client")
    val clientCertificate = ClientCertificate(clientIdentity.key, clientIdentity.chain)
  }
}
