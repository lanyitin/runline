package dev.lawlan.runline.accessors.openai

import dev.lawlan.runline.accessors.ResourceOperationFailure
import dev.lawlan.runline.accessors.fake.FakeOpenAiServer
import dev.lawlan.runline.accessors.support.TestDirectories
import dev.lawlan.runline.accessors.tls.ClientCertificate
import dev.lawlan.runline.accessors.tls.ResourceTls
import dev.lawlan.runline.accessors.tls.TestPki
import dev.lawlan.runline.accessors.tls.TlsFailure
import dev.lawlan.runline.core.ResourceFailure
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * An `openai-compatible` resource behind TLS (WI-52), against the Fake behind real TLS that
 * requires a client certificate: the resource's own trust and client certificate are used, and a
 * failure of TLS is told to the host by its category while the pipeline sees a connection failure.
 */
class OpenAiBindingTlsTest {
  private val key = "sk-tls-0123456789"
  private val server =
      FakeOpenAiServer(
          requiredKey = key,
          tls = pki.serverContext(serviceIdentity, clientsOf = listOf(authority.certificate)),
          requireClientCertificate = true,
      )
  private val bindings = mutableListOf<OpenAiBinding>()
  private val outcomes = CopyOnWriteArrayList<OpenAiOutcome>()
  private val observer =
      object : OpenAiObserver {
        override fun finished(resource: String, endpoint: String, outcome: OpenAiOutcome) {
          outcomes += outcome
        }
      }

  @AfterTest
  fun stop() {
    bindings.forEach { it.close() }
    server.close()
  }

  private fun settings(host: String = "localhost"): OpenAiSettings =
      (OpenAiSettings.parse(
              Json.parseToJsonElement("""{"baseUrl":"${server.baseUrl(host)}"}""").jsonObject
          ) as SettingsResult.Valid)
          .settings

  private fun binding(tls: ResourceTls?, host: String = "localhost") =
      OpenAiBinding("secure", settings(host), OpenAiCredential.Key(key), observer, tls).also {
        bindings += it
      }

  private fun call(binding: OpenAiBinding): Map<*, *> =
      binding.execute(
          "openai.call",
          mapOf("endpoint" to "models.list", "body" to null),
      ) as Map<*, *>

  private val trusted = ResourceTls(listOf(authority.certificate), clientCertificate)

  @Test
  fun `with its trusted certificates and client certificate the resource reaches the service`() {
    assertEquals(200, call(binding(trusted))["status"])
    assertNull(outcomes.single().tlsFailure)
  }

  @Test
  fun `a TLS failure is a connection failure to the pipeline and its category to the host`() {
    val e = assertFailsWith<ResourceOperationFailure> { call(binding(tls = null)) }

    assertEquals(ResourceFailure.CONNECTION_FAILED, e.failure)
    assertEquals(TlsFailure.TRUST_FAILED, outcomes.single().tlsFailure)
  }

  @Test
  fun `the check tells the categories of TLS apart and passes when nothing is wrong`() {
    assertNull(OpenAiProbe.check(settings(), OpenAiCredential.Key(key), 5_000, trusted))
    assertEquals(
        OpenAiProbe.Failure(ResourceFailure.CONNECTION_FAILED, TlsFailure.HOSTNAME_MISMATCH),
        OpenAiProbe.check(settings("127.0.0.1"), OpenAiCredential.Key(key), 5_000, trusted),
    )
    assertEquals(
        OpenAiProbe.Failure(ResourceFailure.CONNECTION_FAILED, TlsFailure.CLIENT_CERT_REJECTED),
        OpenAiProbe.check(
            settings(),
            OpenAiCredential.Key(key),
            5_000,
            ResourceTls(listOf(authority.certificate), null),
        ),
    )
    assertEquals(
        OpenAiProbe.Failure(ResourceFailure.CONNECTION_FAILED, TlsFailure.TRUST_FAILED),
        OpenAiProbe.check(settings(), OpenAiCredential.Key(key), 5_000, null),
    )
  }

  private companion object {
    val pki = TestPki(TestDirectories.forAllTests("pki"))
    val authority = pki.authority("Service CA")
    val serviceIdentity = pki.issue(authority, "localhost", listOf("dns:localhost"))
    val clientIdentity = pki.issue(authority, "runline")
    val clientCertificate = ClientCertificate(clientIdentity.key, clientIdentity.chain)
  }
}
