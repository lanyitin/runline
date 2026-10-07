package dev.lawlan.runline.engine.secret

import dev.lawlan.runline.engine.run.RunState
import dev.lawlan.runline.engine.support.Keystores
import dev.lawlan.runline.engine.support.RunHarness
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.*
import org.slf4j.LoggerFactory

/** A secret the Engine knows is masked where text leaves it for a log (WI-41). */
class SecretMaskingTest {
  private val keystores = Keystores()
  private val closeables = mutableListOf<AutoCloseable>()

  @AfterTest fun tearDown() = closeables.forEach { it.close() }

  private fun known(vararg values: String) =
      SecretMasking.register { values.toList() }.also { closeables += it }

  private fun storeWith(vararg secrets: Pair<String, String>): KeystoreSecretStore =
      KeystoreSecretStore.open(
              keystores.pkcs12("masking.p12", secrets.toMap()),
              SecretValue(Keystores.DEFAULT_PASSWORD),
          )
          .also { closeables += it }

  /** What the Engine's own console appender writes while [log] runs. */
  private fun stdoutOf(log: () -> Unit): String {
    val original = System.out
    val captured = ByteArrayOutputStream()
    System.setOut(PrintStream(captured, true, Charsets.UTF_8))
    try {
      log()
    } finally {
      System.setOut(original)
    }
    return captured.toString(Charsets.UTF_8)
  }

  @Test
  fun `text that holds a known secret has it replaced, and other text is untouched`() {
    known("marker-secret-1")

    assertEquals("a *** b ***", SecretMasking.mask("a marker-secret-1 b marker-secret-1"))
    assertEquals("nothing here", SecretMasking.mask("nothing here"))
    assertEquals("", SecretMasking.mask(""))
  }

  @Test
  fun `a longer secret is masked whole when a shorter one is part of it`() {
    known("abcd", "abcdefgh")

    assertEquals("x *** y", SecretMasking.mask("x abcdefgh y"))
  }

  @Test
  fun `secrets of every registered source are masked and a closed source no longer is`() {
    val first = known("first-secret-1")
    known("second-secret-2")

    assertEquals("*** ***", SecretMasking.mask("first-secret-1 second-secret-2"))
    first.close()
    assertEquals("first-secret-1 ***", SecretMasking.mask("first-secret-1 second-secret-2"))
  }

  @Test
  fun `the secrets of a keystore store are known, follow a reload and go with the store`() {
    val passwordFile = keystores.passwordFile()
    val store = storeWith("key" to "marker-before-9")
    assertEquals("***", SecretMasking.mask("marker-before-9"))

    val path = keystores.dir.resolve("masking.p12")
    keystores.deleteEntry(path, "key", passwordFile)
    keystores.importSecret(path, "key", "marker-after-9", passwordFile)
    store.reload()

    assertEquals("marker-before-9 ***", SecretMasking.mask("marker-before-9 marker-after-9"))
    store.close()
    assertEquals("marker-after-9", SecretMasking.mask("marker-after-9"))
  }

  @Test
  fun `the password of the keystore is masked as well`() {
    storeWith("key" to "marker-value-4")

    assertEquals("pass is ***", SecretMasking.mask("pass is ${Keystores.DEFAULT_PASSWORD}"))
  }

  @Test
  fun `the Engine's console log masks the message, its arguments and a logged exception`() {
    storeWith("key" to "marker-console-5")
    val log = LoggerFactory.getLogger("masking-test")

    val written = stdoutOf {
      log.info("the key is marker-console-5")
      log.info("an argument {}", "marker-console-5")
      log.error("it failed", IllegalStateException("cause says marker-console-5"))
    }

    assertContains(written, "the key is ***")
    assertContains(written, "an argument ***")
    assertContains(written, "cause says ***")
    assertContains(written, "IllegalStateException")
    assertFalse(written.contains("marker-console-5"), written)
  }

  @Test
  fun `a line a run writes to its log is masked in the stored log`() {
    storeWith("key" to "marker-run-log-7")
    RunHarness().use { h ->
      val hash = h.upload("leaky", """System.out.println("echoed: marker-run-log-7 end");""")

      val id = h.start(hash, "leaky")
      h.await(id, RunState.SUCCEEDED)

      assertEquals(listOf("echoed: *** end"), h.runStore.read(id, 0, 100).map { it.line })
    }
  }

  @Test
  fun `the failure a run ends with is masked in the record of the run`() {
    storeWith("key" to "marker-failure-2")
    RunHarness().use { h ->
      val hash =
          h.upload(
              "rejected",
              """throw new IllegalStateException("the service said marker-failure-2 is wrong");""",
          )

      val id = h.start(hash, "rejected")
      val run = h.await(id, RunState.FAILED)

      assertEquals("the service said *** is wrong", run.failure!!.message)
      assertFalse(run.failure!!.trace.contains("marker-failure-2"), run.failure!!.trace)
      assertTrue(run.failure!!.trace.contains("the service said ***"), run.failure!!.trace)
    }
  }
}
