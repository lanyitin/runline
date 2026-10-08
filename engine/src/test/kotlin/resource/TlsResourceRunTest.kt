package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.accessors.fake.FakeOpenAiServer
import dev.lawlan.runline.accessors.tls.TestPki
import dev.lawlan.runline.analyzer.AllowListEntry
import dev.lawlan.runline.engine.run.RunState
import dev.lawlan.runline.engine.secret.KeystoreSecretStore
import dev.lawlan.runline.engine.secret.SecretValue
import dev.lawlan.runline.engine.support.Keystores
import dev.lawlan.runline.engine.support.RunHarness
import dev.lawlan.runline.engine.support.RunHarness.Companion.holdUntilReleased
import dev.lawlan.runline.engine.support.RunHarness.Companion.usingTyped
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import kotlin.test.*

/**
 * Rotating the certificates of an `openai-compatible` resource through real runs (WI-52): a real
 * keystore whose client certificate and trusted certificate are replaced and reloaded, and the Fake
 * behind real TLS that requires a client certificate and says which one each request came with. A
 * run keeps the TLS it got; the runs after a reload get the new one.
 */
class TlsResourceRunTest {
  private val keystores = Keystores()
  private val passwordFile = keystores.passwordFile()
  private val server =
      FakeOpenAiServer(
          tls = pki.serverContext(serviceIdentity, clientsOf = listOf(authority.certificate)),
          requireClientCertificate = true,
      )
  private val keystore: Path =
      keystores.pkcs12("rotate.p12").also {
        pki.addTrusted(it, "internal-ca", authority, passwordFile)
        pki.addPrivateKey(it, "app-client", firstClient, passwordFile)
      }
  private val secrets = KeystoreSecretStore.open(keystore, SecretValue(Keystores.DEFAULT_PASSWORD))
  private val h =
      RunHarness(
          maxConcurrent = 3,
          resourceWaitTimeout = Duration.ofHours(1),
          secrets = secrets,
          allowList =
              listOf(
                      "java.lang",
                      "java.util",
                      "java.io",
                      "java.time",
                      "kotlin",
                      "org.jetbrains.annotations",
                  )
                  .map { AllowListEntry(it) },
      )

  @AfterTest
  fun close() {
    h.close()
    secrets.close()
    server.close()
  }

  private fun replace(change: (Path) -> Unit) {
    val copy = keystore.resolveSibling("rotate.next.p12")
    Files.copy(keystore, copy, StandardCopyOption.REPLACE_EXISTING)
    change(copy)
    Files.move(copy, keystore, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    secrets.reload()
  }

  private val call =
      """
      OpenAiAccessor lemon = context.getAccessors().openAiCompatible("lemon");
      String result;
      try {
        OpenAiResponse r = lemon.call(new OpenAiRequest("models.list", null, java.util.Collections.<String,String>emptyMap(), java.util.Collections.<String,String>emptyMap(), new OpenAiTimeouts()));
        result = "ok|" + r.getStatus();
      } catch (ResourceAccessException e) {
        result = e.getFailure().name();
      }
      context.getFiles().writeText(FileScope.PIPELINE_SHARED, "outcome", result);
      """
          .trimIndent()

  private fun outcome(pipeline: String) = Files.readString(h.shared(pipeline, "outcome"))

  @Test
  fun `a run keeps the certificates it got, and the runs after a reload get the new ones`() {
    h.defineOpenAi(
        "lemon",
        """{"baseUrl":"${server.baseUrl("localhost")}","trustAliases":["internal-ca"],"clientCertAlias":"app-client"}""",
        capacity = 3,
    )
    val holding =
        h.upload(
            "holding",
            holdUntilReleased("started") + "\n" + call,
            declaration = usingTyped("lemon" to "openai-compatible"),
        )
    val calling =
        h.upload("calling", call, declaration = usingTyped("lemon" to "openai-compatible"))
    val first = h.start(holding, "holding")
    h.awaitFile(h.shared("holding", "started"))

    // The client certificate is rotated: the next run presents the new one.
    replace {
      keystores.deleteEntry(it, "app-client", passwordFile)
      pki.addPrivateKey(it, "app-client", secondClient, passwordFile)
    }
    val second = h.start(calling, "calling")
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(second).state)
    assertEquals("ok|200", outcome("calling"))

    // The trusted certificate is replaced by one the service's certificate does not lead to.
    replace {
      keystores.deleteEntry(it, "internal-ca", passwordFile)
      pki.addTrusted(it, "internal-ca", pki.authority("Unrelated CA"), passwordFile)
    }
    val third = h.start(calling, "calling")
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(third).state)
    assertEquals("CONNECTION_FAILED", outcome("calling"))

    // The run that held the first generation still has its trust and its certificate.
    Files.writeString(h.shared("holding", "release"), "x")
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(first).state)
    assertEquals("ok|200", outcome("holding"))
    assertEquals(
        listOf("CN=runline-client-2", "CN=runline-client-1"),
        server.requests.map { it.clientCertificate },
    )
  }

  private companion object {
    val pki = TestPki()
    val authority = pki.authority("Internal CA")
    val serviceIdentity = pki.issue(authority, "localhost", listOf("dns:localhost"))
    val firstClient = pki.issue(authority, "runline-client-1")
    val secondClient = pki.issue(authority, "runline-client-2")
  }
}
