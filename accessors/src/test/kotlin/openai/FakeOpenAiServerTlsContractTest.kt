package dev.lawlan.runline.accessors.openai

import dev.lawlan.runline.accessors.fake.ContractTarget
import dev.lawlan.runline.accessors.fake.FakeOpenAiServer
import dev.lawlan.runline.accessors.fake.OpenAiServerContract
import dev.lawlan.runline.accessors.tls.TestPki
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.test.AfterTest

/**
 * The Fake behind TLS that requires a client certificate (WI-52) keeps the same protocol contract:
 * the contract's own JDK client trusts the Fake's authority and presents a certificate it issued,
 * so TLS changes nothing of what the Engine relies on.
 */
class FakeOpenAiServerTlsContractTest : OpenAiServerContract() {
  private val server =
      FakeOpenAiServer(
          requiredKey = "sk-contract-key",
          tls = pki.serverContext(serverIdentity, clientsOf = listOf(authority.certificate)),
          requireClientCertificate = true,
      )

  override fun target() =
      ContractTarget(
          server.baseUrl("localhost"),
          "fake-model",
          "sk-contract-key",
          sslContext = client,
      )

  @AfterTest fun stop() = server.close()

  private companion object {
    val pki = TestPki()
    val authority = pki.authority("Contract CA")
    val serverIdentity = pki.issue(authority, "localhost", listOf("dns:localhost"))
    val clientIdentity = pki.issue(authority, "contract-client")

    /** A client of the JDK alone: it trusts the authority and presents the client certificate. */
    val client: SSLContext = run {
      val trust = KeyStore.getInstance("PKCS12").apply { load(null, null) }
      trust.setCertificateEntry("ca", authority.certificate)
      val keys = KeyStore.getInstance("PKCS12").apply { load(null, null) }
      val password = "contract".toCharArray()
      keys.setKeyEntry("client", clientIdentity.key, password, clientIdentity.chain.toTypedArray())
      SSLContext.getInstance("TLS").apply {
        init(
            KeyManagerFactory.getInstance("PKIX").apply { init(keys, password) }.keyManagers,
            TrustManagerFactory.getInstance("PKIX").apply { init(trust) }.trustManagers,
            null,
        )
      }
    }
  }
}
