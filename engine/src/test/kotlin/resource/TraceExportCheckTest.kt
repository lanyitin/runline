package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.engine.config.TraceExportTuning
import dev.lawlan.runline.engine.secret.KeystoreSecretStore
import dev.lawlan.runline.engine.secret.NoSecretStore
import dev.lawlan.runline.engine.secret.SecretStore
import dev.lawlan.runline.engine.secret.SecretValue
import dev.lawlan.runline.engine.support.Keystores
import dev.lawlan.runline.engine.support.OtlpReceiver
import dev.lawlan.runline.engine.support.QUICK_TRACE_EXPORT
import dev.lawlan.runline.engine.support.TraceExportRig
import java.net.ServerSocket
import java.nio.file.Files
import java.time.Duration
import kotlin.test.*

/**
 * The check of a `trace-export` resource (ADR-019 decision 8, ADR-46): it looks at the real
 * endpoint with the real credential, without sending a span, and names what stops the resource as a
 * category. A missing alias or no keystore is a category too, and never a failure of a run.
 */
class TraceExportCheckTest {
  private val keystores = Keystores()
  private val passwordFile = keystores.passwordFile()
  private val keystore =
      keystores.pkcs12("check.p12", mapOf("langfuse-auth" to "Basic Y2hlY2s=", "bad" to "ok"))
  private val secrets =
      KeystoreSecretStore.open(keystore, SecretValue(Files.readString(passwordFile).trim()))
  private val receiver = OtlpReceiver()
  private val rigs = mutableListOf<TraceExportRig>()

  private fun rig(
      store: SecretStore = secrets,
      tuning: TraceExportTuning = QUICK_TRACE_EXPORT,
  ) = TraceExportRig(store, tuning).also { rigs += it }

  @AfterTest
  fun close() {
    rigs.forEach { it.close() }
    receiver.close()
    secrets.close()
  }

  @Test
  fun `an endpoint that takes the credential passes, and no span is sent`() {
    val rig = rig()

    val failure = rig.behavior.check(rig.resource(receiver.endpoint))

    assertNull(failure)
    val request = receiver.requests.single()
    assertEquals("Basic Y2hlY2s=", request.authorization)
    assertTrue(request.spans.isEmpty())
  }

  @Test
  fun `an endpoint that refuses the credential is rejected`() {
    receiver.answer = { 401 }
    val rig = rig()

    assertEquals(CheckFailure.REJECTED, rig.behavior.check(rig.resource(receiver.endpoint)))
  }

  @Test
  fun `an endpoint that fails is a server error`() {
    receiver.answer = { 500 }
    val rig = rig()

    assertEquals(CheckFailure.SERVER_ERROR, rig.behavior.check(rig.resource(receiver.endpoint)))
  }

  @Test
  fun `an endpoint that redirects is blocked, because the address stays what the administrator gave`() {
    receiver.answer = { 302 }
    val rig = rig()

    assertEquals(
        CheckFailure.REDIRECT_BLOCKED,
        rig.behavior.check(rig.resource(receiver.endpoint)),
    )
  }

  @Test
  fun `an endpoint nobody listens on cannot be connected to`() {
    val closedPort = ServerSocket(0).use { it.localPort }
    val rig = rig()

    assertEquals(
        CheckFailure.CONNECTION_FAILED,
        rig.behavior.check(rig.resource("http://127.0.0.1:$closedPort/v1/traces")),
    )
  }

  @Test
  fun `an endpoint that does not answer is a timeout`() {
    receiver.hang = true
    val rig = rig()
    val behavior = TraceExportBehavior(secrets, Duration.ofMillis(300), rig.exporters)

    assertEquals(CheckFailure.TIMEOUT, behavior.check(rig.resource(receiver.endpoint)))
  }

  @Test
  fun `an alias the keystore does not have is a missing alias`() {
    val rig = rig()

    assertEquals(
        CheckFailure.ALIAS_MISSING,
        rig.behavior.check(rig.resource(receiver.endpoint, alias = "nothing")),
    )
    assertTrue(receiver.requests.isEmpty())
  }

  @Test
  fun `no keystore at all is a missing alias too`() {
    val rig = rig(NoSecretStore)

    assertEquals(
        CheckFailure.ALIAS_MISSING,
        rig.behavior.check(rig.resource(receiver.endpoint)),
    )
  }

  @Test
  fun `a credential of every printable ASCII character goes out as it is in the keystore`() {
    val everyCharacter = "A" + (0x20..0x7e).map { it.toChar() }.joinToString("")
    keystores.importSecret(keystore, "all-printable", everyCharacter, passwordFile)
    secrets.reload()
    val rig = rig()

    assertNull(rig.behavior.check(rig.resource(receiver.endpoint, alias = "all-printable")))

    assertEquals(everyCharacter, receiver.requests.single().authorization)
  }

  @Test
  fun `a credential that is not printable ASCII cannot be used, which the check says`() {
    keystores.importSecret(keystore, "accented", "Basic café", passwordFile)
    secrets.reload()
    val rig = rig()

    assertEquals(
        CheckFailure.ALIAS_INVALID,
        rig.behavior.check(rig.resource(receiver.endpoint, alias = "accented")),
    )
    assertTrue(receiver.requests.isEmpty())
  }

  @Test
  fun `a certificate under the alias of the credential is a wrong type`() {
    keystores.privateKey(keystore, "a-certificate", passwordFile)
    secrets.reload()
    val rig = rig()

    assertEquals(
        CheckFailure.ALIAS_WRONG_TYPE,
        rig.behavior.check(rig.resource(receiver.endpoint, alias = "a-certificate")),
    )
  }
}
