package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.accessors.tls.TestPki
import dev.lawlan.runline.engine.secret.KeystoreSecretStore
import dev.lawlan.runline.engine.secret.SecretValue
import dev.lawlan.runline.engine.support.Keystores
import dev.lawlan.runline.engine.support.OtlpReceiver
import dev.lawlan.runline.engine.support.TraceExportRig
import java.nio.file.Files
import kotlin.test.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The TLS of the endpoint of a `trace-export` resource is the one of every typed resource (ADR-019
 * decision 12, ADR-46 decision 8): the certificates named by `trustAliases` replace the JVM's
 * trust, the host is always checked, and an endpoint whose certificate does not lead to a trusted
 * one gets no span. The receiver is a real one behind real TLS, with certificates made by `keytool`
 * and kept in a real keystore.
 */
class TraceExportTlsTest {
  private val pki = TestPki()
  private val authority = pki.authority("Internal CA")
  private val receiver =
      OtlpReceiver(pki.serverContext(pki.issue(authority, "localhost", listOf("dns:localhost"))))
  private val keystores = Keystores()
  private val passwordFile = keystores.passwordFile()
  private val keystore =
      keystores.pkcs12("tls.p12", mapOf("langfuse-auth" to "Basic dGxz")).also {
        pki.addTrusted(it, "internal-ca", authority, passwordFile)
        pki.addTrusted(it, "unrelated-ca", pki.authority("Unrelated CA"), passwordFile)
      }
  private val secrets =
      KeystoreSecretStore.open(keystore, SecretValue(Files.readString(passwordFile).trim()))
  private val rig = TraceExportRig(secrets)

  @AfterTest
  fun close() {
    rig.close()
    receiver.close()
    secrets.close()
  }

  private fun resource(vararg trust: String): SharedResource =
      rig.resource(receiver.endpoint).let {
        if (trust.isEmpty()) it
        else
            it.copy(
                settings =
                    JsonObject(
                        it.settings +
                            ("trustAliases" to JsonArray(trust.map { a -> JsonPrimitive(a) }))
                    )
            )
      }

  private fun await(what: String, seconds: Long = 15, condition: () -> Boolean) {
    val deadline = System.nanoTime() + seconds * 1_000_000_000
    while (!condition()) {
      check(System.nanoTime() < deadline) { "gave up waiting for $what" }
      Thread.sleep(10)
    }
  }

  @Test
  fun `spans reach an https endpoint whose certificate leads to a trusted one`() {
    val binding = rig.bind(resource("internal-ca"))

    rig.submit(binding, "s", "a")
    await("the span") { receiver.spans.isNotEmpty() }

    assertEquals("s", receiver.spans.single().name)
    assertNull(rig.behavior.check(resource("internal-ca")))
  }

  @Test
  fun `an endpoint whose certificate does not lead to a trusted one gets nothing`() {
    val binding = rig.bind(resource("unrelated-ca"))

    rig.submit(binding, "s", "a")
    await("the batch dropped") {
      rig.count("runline.trace_export.spans.dropped", "reason" to "export_failed") == 1L
    }

    assertTrue(receiver.spans.isEmpty())
    assertEquals(CheckFailure.TRUST_FAILED, rig.behavior.check(resource("unrelated-ca")))
  }

  @Test
  fun `an endpoint whose certificate is for another host gets nothing, whatever it is trusted for`() {
    val byAddress =
        resource("internal-ca").let {
          it.copy(
              settings =
                  JsonObject(
                      it.settings +
                          ("endpoint" to
                              JsonPrimitive(receiver.endpoint.replace("localhost", "127.0.0.1")))
                  )
          )
        }
    val binding = rig.bind(byAddress)

    rig.submit(binding, "s", "a")
    await("the batch dropped") {
      rig.count("runline.trace_export.spans.dropped", "reason" to "export_failed") == 1L
    }

    assertTrue(receiver.spans.isEmpty())
    assertEquals(CheckFailure.HOSTNAME_MISMATCH, rig.behavior.check(byAddress))
  }

  @Test
  fun `without a trusted certificate the JVM's own trust is used, which does not know the authority`() {
    val binding = rig.bind(resource())

    rig.submit(binding, "s", "a")
    await("the batch dropped") {
      rig.count("runline.trace_export.spans.dropped", "reason" to "export_failed") == 1L
    }

    assertTrue(receiver.spans.isEmpty())
    assertNotNull(rig.behavior.check(resource()))
  }

  @Test
  fun `a trusted alias the keystore does not have leaves the resource unable to export, not failing`() {
    val binding = rig.bind(resource("nobody"))

    rig.submit(binding, "s", "a")

    assertEquals(1L, rig.count("runline.trace_export.spans.dropped", "reason" to "unavailable"))
    assertEquals(CheckFailure.ALIAS_MISSING, rig.behavior.check(resource("nobody")))
  }
}
