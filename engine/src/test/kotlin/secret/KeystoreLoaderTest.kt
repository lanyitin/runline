package dev.lawlan.runline.engine.secret

import dev.lawlan.runline.engine.support.CapturedLogs
import dev.lawlan.runline.engine.support.Keystores
import dev.lawlan.runline.engine.support.Keytool
import kotlin.test.*

/** Reading real PKCS12 files written by the JDK's own `keytool` (WI-41). */
class KeystoreLoaderTest {
  private val keystores = Keystores()
  private val password = SecretValue(Keystores.DEFAULT_PASSWORD)

  private fun load(file: java.nio.file.Path) = KeystoreLoader(file, password).load()

  @Test
  fun `an entry of each of the three kinds is recognised and listed under its lower case alias`() {
    val passwordFile = keystores.passwordFile()
    val file = keystores.pkcs12("kinds.p12", mapOf("Db.Password" to "s3cret value!"))
    keystores.privateKey(file, "Client-Key", passwordFile)
    keystores.trustedCertificate(file, "Internal-CA", passwordFile)

    val snapshot = load(file)

    assertEquals(
        listOf(
            SecretEntryInfo("client-key", EntryKind.PRIVATE_KEY, AliasStatus.FOUND),
            SecretEntryInfo("db.password", EntryKind.SECRET, AliasStatus.FOUND),
            SecretEntryInfo("internal-ca", EntryKind.TRUSTED_CERTIFICATE, AliasStatus.FOUND),
        ),
        snapshot.entries(),
    )
  }

  @Test
  fun `the value of a secret comes back exactly, spaces and symbols included`() {
    val value = "a b-c_d!@#\$%^&*()+=[]{};:'\",.<>/?\\|~`" + "x".repeat(700)
    val file = keystores.pkcs12("values.p12", mapOf("api-key" to value))

    val found = assertIs<SecretLookup.Found>(load(file).lookup("api-key"))

    assertEquals(value, found.value.reveal())
  }

  @Test
  fun `a secret that is not printable ASCII is invalid_secret, not used, and the others still are`() {
    val passwordFile = keystores.passwordFile()
    // The tool's password encoding breaks a non-ASCII character beyond recovery (ADR-019).
    val file =
        keystores.pkcs12(
            "invalid.p12",
            mapOf("good" to "plain value", "accented" to "caf\u00e9", "bell" to "a\u0007b"),
        )
    Keytool.run(
        "-genseckey",
        "-alias",
        "binary",
        "-keyalg",
        "AES",
        "-keysize",
        "128",
        "-keystore",
        file.toString(),
        "-storetype",
        "pkcs12",
        "-storepass:file",
        passwordFile.toString(),
    )

    val snapshot = load(file)

    assertEquals(
        listOf(
            "accented" to AliasStatus.INVALID_SECRET,
            "bell" to AliasStatus.INVALID_SECRET,
            "binary" to AliasStatus.INVALID_SECRET,
            "good" to AliasStatus.FOUND,
        ),
        snapshot.entries().map { it.alias to it.status },
    )
    assertEquals(SecretLookup.Invalid, snapshot.lookup("accented"))
    assertEquals(SecretLookup.Invalid, snapshot.lookup("binary"))
    assertEquals(SecretLookup.Invalid, snapshot.lookup("bell"))
    assertIs<SecretLookup.Found>(snapshot.lookup("good"))
  }

  @Test
  fun `a rejected alias is logged with its alias and its category and without any value`() {
    val file = keystores.pkcs12("logged.p12", mapOf("accented" to "caf\u00e9"))

    val lines =
        CapturedLogs().use { logs ->
          load(file)
          logs.lines
        }

    val line = lines.single { it.contains("accented") }
    assertTrue(line.contains("invalid_secret"), line)
    assertTrue(line.startsWith("WARN"), line)
    assertFalse(line.contains("caf"), "the value must not be in the log: $line")
    assertFalse(lines.any { it.contains(Keystores.DEFAULT_PASSWORD) })
  }

  private fun failureOf(file: java.nio.file.Path, secret: String = Keystores.DEFAULT_PASSWORD) =
      assertFailsWith<KeystoreOpenException> { KeystoreLoader(file, SecretValue(secret)).load() }
          .failure

  /** Every file the loader must refuse, made by the real tool or damaged from such a file. */
  private fun refusedFiles(): Map<String, Pair<java.nio.file.Path, OpenFailure>> {
    val good = keystores.pkcs12("good.p12", mapOf("a" to "value-a"))
    val bytes = java.nio.file.Files.readAllBytes(good)
    return mapOf(
        "a JKS file" to (keystores.otherFormat("legacy.jks", "JKS") to OpenFailure.WRONG_FORMAT),
        "a JCEKS file" to
            (keystores.otherFormat("legacy.jceks", "JCEKS") to OpenFailure.WRONG_FORMAT),
        "a file that is not a keystore" to
            (keystores.dir.resolve("garbage.txt").also {
              java.nio.file.Files.writeString(it, "this is not a keystore at all\n".repeat(50))
            } to OpenFailure.WRONG_FORMAT),
        "a PKCS12 file cut short" to
            (keystores.dir.resolve("cut.p12").also {
              java.nio.file.Files.write(it, bytes.copyOf(bytes.size / 2))
            } to OpenFailure.CORRUPT),
        "an empty file" to
            (keystores.dir.resolve("empty.p12").also {
              java.nio.file.Files.write(it, ByteArray(0))
            } to OpenFailure.CORRUPT),
        "a file that is not there" to
            (keystores.dir.resolve("none.p12") to OpenFailure.FILE_MISSING),
        "a directory" to (keystores.dir to OpenFailure.UNREADABLE),
    )
  }

  @Test
  fun `only a PKCS12 file is accepted and each other file is refused with its category`() {
    for ((what, case) in refusedFiles()) {
      assertEquals(case.second, failureOf(case.first), what)
    }
  }

  @Test
  fun `a wrong password is its own category, and the right one still opens the file`() {
    val file = keystores.pkcs12("pw.p12", mapOf("a" to "value-a"))

    assertEquals(OpenFailure.WRONG_PASSWORD, failureOf(file, "not-the-password"))
    assertIs<SecretLookup.Found>(load(file).lookup("a"))
  }

  @Test
  fun `the category is the same whether the JDK's keystore type compatibility is on or off`() {
    val cases =
        refusedFiles() +
            ("a wrong password" to
                (keystores.pkcs12("wp.p12", mapOf("a" to "v")) to OpenFailure.WRONG_PASSWORD))
    val before = java.security.Security.getProperty("keystore.type.compat")
    try {
      for (setting in listOf("true", "false")) {
        java.security.Security.setProperty("keystore.type.compat", setting)
        for ((what, case) in cases) {
          val secret = if (what == "a wrong password") "wrong" else Keystores.DEFAULT_PASSWORD
          assertEquals(case.second, failureOf(case.first, secret), "$what, compat=$setting")
        }
      }
    } finally {
      java.security.Security.setProperty("keystore.type.compat", before ?: "true")
    }
  }

  /**
   * The behaviour of the JDK this relies on not to rely on (WI-41, ADR-019 decision 6): with its
   * default, a request for a PKCS12 keystore also opens a JKS file, which is why the loader checks
   * the format itself. Revalidate with every JDK upgrade.
   */
  @Test
  fun `the JDK itself opens a JKS file as PKCS12 unless its compatibility setting is off`() {
    val jks = keystores.otherFormat("compat.jks", "JKS")
    val jceks = keystores.otherFormat("compat.jceks", "JCEKS")
    fun opens(file: java.nio.file.Path): Boolean = runCatching {
      java.io.FileInputStream(file.toFile()).use {
        java.security.KeyStore.getInstance("PKCS12")
            .load(it, Keystores.DEFAULT_PASSWORD.toCharArray())
      }
    }
        .isSuccess
    val before = java.security.Security.getProperty("keystore.type.compat")
    try {
      java.security.Security.setProperty("keystore.type.compat", "true")
      assertTrue(opens(jks), "JKS is opened as PKCS12 with the compatibility on")
      assertFalse(opens(jceks), "JCEKS is not opened as PKCS12 either way")
      java.security.Security.setProperty("keystore.type.compat", "false")
      assertFalse(opens(jks), "JKS is not opened with the compatibility off")
    } finally {
      java.security.Security.setProperty("keystore.type.compat", before ?: "true")
    }
  }

  @Test
  fun `what the exception says is the category and nothing of the path or the password`() {
    val file = keystores.pkcs12("quiet.p12", mapOf("a" to "value-a"))

    val thrown =
        assertFailsWith<KeystoreOpenException> {
          KeystoreLoader(file, SecretValue("wrong-password-marker")).load()
        }

    val text = generateSequence<Throwable>(thrown) { it.cause }.joinToString { "${it.message}" }
    assertEquals("The keystore cannot be opened: wrong_password", thrown.message)
    assertFalse(text.contains("wrong-password-marker"))
    assertFalse(text.contains(file.toString()))
  }
}
