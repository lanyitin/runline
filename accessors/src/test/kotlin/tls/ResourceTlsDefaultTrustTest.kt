package dev.lawlan.runline.accessors.tls

import dev.lawlan.runline.accessors.fake.FakeOpenAiServer
import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import kotlin.io.path.outputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a resource's trust is with and without `trustAliases`, against a service whose certificate
 * the JVM's default trust accepts (WI-52): that is a publicly trusted chain, as far as the JVM is
 * concerned. A test cannot hold the key of a public authority, so the JVM is made to trust an
 * authority of the test by default, in a JVM of its own (`javax.net.ssl.trustStore` on its command
 * line), with nothing else changed; the resource's code runs there as it does in the Engine.
 */
class ResourceTlsDefaultTrustTest {
  private val pki = TestPki()
  private val publicAuthority = pki.authority("Public Root CA")
  private val internalAuthority = pki.authority("Internal CA")
  private val server =
      FakeOpenAiServer(
          tls = pki.serverContext(pki.issue(publicAuthority, "localhost", listOf("dns:localhost")))
      )

  @AfterTest fun stop() = server.close()

  /** A JVM whose default trust is the public authority only. */
  private fun probe(vararg trusted: Path): String {
    val defaults = pki.dir.resolve("default-trust.p12")
    val store = KeyStore.getInstance("PKCS12").apply { load(null, null) }
    store.setCertificateEntry("public-root", publicAuthority.certificate)
    defaults.outputStream().use { store.store(it, "trust-pass".toCharArray()) }
    val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
    val builder =
        ProcessBuilder(
                java,
                "-Djavax.net.ssl.trustStore=$defaults",
                "-Djavax.net.ssl.trustStoreType=PKCS12",
                "-Djavax.net.ssl.trustStorePassword=trust-pass",
                "-cp",
                System.getProperty("java.class.path"),
                "dev.lawlan.runline.accessors.tls.TrustProbeMainKt",
                server.baseUrl("localhost") + "/models",
                *trusted.map { it.toString() }.toTypedArray(),
            )
            .redirectErrorStream(true)
    // Only what is on the command line decides the default trust of that JVM.
    builder.environment().remove("JAVA_TOOL_OPTIONS")
    val process = builder.start()
    val output = process.inputStream.readAllBytes().decodeToString()
    check(process.waitFor(60, TimeUnit.SECONDS)) { "the probe did not end" }
    return output.lines().last { it.startsWith("status") || it.startsWith("failure") }
  }

  @Test
  fun `without trust aliases the JVM's default trust is used and accepts the service`() {
    assertEquals("status 200", probe())
  }

  @Test
  fun `with trust aliases only those are trusted, so the publicly trusted service is refused`() {
    assertEquals("failure trust_failed", probe(pki.export(internalAuthority)))
  }
}
