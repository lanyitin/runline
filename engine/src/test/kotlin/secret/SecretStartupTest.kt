package dev.lawlan.runline.engine.secret

import dev.lawlan.runline.engine.support.Keystores
import dev.lawlan.runline.engine.support.configureEngine
import io.ktor.server.plugins.di.*
import io.ktor.server.testing.*
import kotlin.test.*

/** The Engine with and without a keystore, through its real startup (WI-41). */
class SecretStartupTest {
  private val keystores = Keystores()
  private val password = Keystores.DEFAULT_PASSWORD

  private fun keystoreSettings(file: java.nio.file.Path, secret: String = password) =
      mapOf("secrets.keystorePath" to "$file", "secrets.password" to secret)

  private fun causes(e: Throwable) = generateSequence(e) { it.cause }.toList()

  @Test
  fun `an Engine given no keystore starts and finds no secret`() = testApplication {
    configureEngine()
    startApplication()

    val store = application.dependencies.resolve<SecretStore>()

    assertFalse(store.configured)
    assertEquals(SecretLookup.Missing, store.lookup("any"))
  }

  @Test
  fun `an Engine given a keystore starts with its secrets loaded`() = testApplication {
    val file = keystores.pkcs12("start.p12", mapOf("Db-Pass" to "value-1"))
    configureEngine(overrides = keystoreSettings(file))
    startApplication()

    val store = application.dependencies.resolve<SecretStore>()

    assertTrue(store.configured)
    assertEquals(listOf("db-pass"), store.entries().map { it.alias })
  }

  @Test
  fun `a keystore that cannot be opened stops the start, with the category only`() {
    val good = keystores.pkcs12("good.p12", mapOf("a" to "value"))
    val cut = keystores.dir.resolve("cut.p12")
    java.nio.file.Files.write(
        cut,
        java.nio.file.Files.readAllBytes(good).let { it.copyOf(it.size / 2) },
    )
    val cases =
        mapOf(
            OpenFailure.FILE_MISSING to keystoreSettings(keystores.dir.resolve("none.p12")),
            OpenFailure.WRONG_PASSWORD to keystoreSettings(good, "marker-wrong-password"),
            OpenFailure.CORRUPT to keystoreSettings(cut),
            OpenFailure.WRONG_FORMAT to
                keystoreSettings(keystores.otherFormat("legacy.jks", "JKS")),
        )
    for ((expected, settings) in cases) {
      val e = assertFails {
        testApplication {
          configureEngine(overrides = settings)
          startApplication()
        }
      }

      val failure = causes(e).filterIsInstance<KeystoreOpenException>().firstOrNull()
      assertNotNull(failure, "$expected: $e")
      assertEquals(expected, failure.failure)
      val said = causes(e).joinToString { "${it.message}" }
      assertFalse(said.contains("marker-wrong-password"), said)
      assertFalse(said.contains(password), said)
      assertFalse(said.contains(keystores.dir.toString()), said)
    }
  }

  @Test
  fun `a password given twice stops the start and names the keys`() {
    val file = keystores.pkcs12("twice.p12", mapOf("a" to "value"))
    val e = assertFails {
      testApplication {
        configureEngine(
            overrides =
                keystoreSettings(file) + ("secrets.passwordFile" to "${keystores.passwordFile()}")
        )
        startApplication()
      }
    }

    assertTrue(
        causes(e).any {
          it.message?.contains("secrets.passwordFile") == true &&
              it.message?.contains("secrets.password ") == true
        },
        "$e",
    )
  }
}
