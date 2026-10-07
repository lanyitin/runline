package dev.lawlan.runline.engine.config

import io.ktor.server.config.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Where the keystore and its password come from (WI-41, ADR-019 decision 6; 12-factor config). */
class SecretSettingsTest {
  private val dir: Path = Files.createTempDirectory("secret-settings")
  private val sharedRoot = dir.resolve("shared")
  private val runRoot = dir.resolve("runs")
  private val resourceRoot = dir.resolve("resources")
  private val keystore = dir.resolve("config/keystore.p12")

  private fun parse(vararg overrides: Pair<String, String>) =
      EngineConfig.from(
          MapApplicationConfig(
              *(mapOf(
                      "postgres.url" to "jdbc:postgresql://db/runline",
                      "postgres.user" to "runline",
                      "postgres.password" to "pw",
                      "auth.tokens" to "root:admin:tok-root-5678",
                      "workspace.sharedRoot" to sharedRoot.toString(),
                      "workspace.runRoot" to runRoot.toString(),
                      "workspace.maxBytes" to "1048576",
                      "workspace.failedRunRetentionSeconds" to "3600",
                      "runs.maxConcurrent" to "4",
                      "runs.runtimeDir" to "/opt/runline/run-runtime",
                      "resources.root" to resourceRoot.toString(),
                  ) + overrides)
                  .toList()
                  .toTypedArray()
          )
      )

  private fun passwordFile(content: String): Path =
      Files.writeString(dir.resolve("password-${content.hashCode()}"), content)

  @Test
  fun `no keystore is configured when none of the settings is given`() {
    assertNull(parse().secrets)
  }

  @Test
  fun `a keystore with its password in an environment variable`() {
    val secrets =
        parse("secrets.keystorePath" to "$keystore", "secrets.password" to "env-password-1")
            .secrets!!

    assertEquals(keystore, secrets.keystore)
    assertEquals("env-password-1", secrets.password.reveal())
  }

  @Test
  fun `a password file gives the first line, without its line end`() {
    for (content in
        listOf("file-password-1", "file-password-1\n", "file-password-1\r\nignored\n")) {
      val secrets =
          parse(
                  "secrets.keystorePath" to "$keystore",
                  "secrets.passwordFile" to "${passwordFile(content)}",
              )
              .secrets!!

      assertEquals("file-password-1", secrets.password.reveal(), content)
    }
  }

  @Test
  fun `the settings never print the password`() {
    val secrets =
        parse("secrets.keystorePath" to "$keystore", "secrets.password" to "marker-password-77")
            .secrets!!

    assertFalse(secrets.toString().contains("marker-password-77"), secrets.toString())
  }

  @Test
  fun `both a password file and a password is a configuration error that says no value`() {
    val e =
        assertFailsWith<ConfigurationException> {
          parse(
              "secrets.keystorePath" to "$keystore",
              "secrets.passwordFile" to "${passwordFile("from-file-91")}",
              "secrets.password" to "from-env-91",
          )
        }

    assertTrue(e.message!!.contains("secrets.passwordFile"), e.message)
    assertTrue(e.message!!.contains("secrets.password"), e.message)
    assertFalse(e.message!!.contains("from-file-91") || e.message!!.contains("from-env-91"))
  }

  @Test
  fun `a keystore without a password source, and a password source without a keystore, are errors`() {
    val noPassword =
        assertFailsWith<ConfigurationException> { parse("secrets.keystorePath" to "$keystore") }
    val noKeystore = assertFailsWith<ConfigurationException> { parse("secrets.password" to "p") }
    val noKeystoreForFile =
        assertFailsWith<ConfigurationException> {
          parse("secrets.passwordFile" to "${passwordFile("x")}")
        }

    assertTrue(noPassword.message!!.contains("secrets.passwordFile"), noPassword.message)
    assertTrue(noPassword.message!!.contains("secrets.password"), noPassword.message)
    assertTrue(noKeystore.message!!.contains("secrets.keystorePath"), noKeystore.message)
    assertTrue(noKeystoreForFile.message!!.contains("secrets.keystorePath"))
  }

  @Test
  fun `a password file that is missing, a directory or empty is an error naming the key only`() {
    val missing = dir.resolve("no-such-password-file")
    val empty = passwordFile("")
    for (file in listOf(missing, dir, empty)) {
      val e =
          assertFailsWith<ConfigurationException>("$file") {
            parse("secrets.keystorePath" to "$keystore", "secrets.passwordFile" to "$file")
          }
      assertTrue(e.message!!.contains("secrets.passwordFile"), e.message)
      assertFalse(e.message!!.contains(file.toString()), "no path in: ${e.message}")
    }
  }

  @Test
  fun `the keystore must not be in the shared directory, a run directory or the resource root`() {
    val clashes =
        listOf(
            "workspace.sharedRoot" to sharedRoot.resolve("keystore.p12"),
            "workspace.runRoot" to runRoot.resolve("deep/er/keystore.p12"),
            "resources.root" to resourceRoot.resolve("keystore.p12"),
            "resources.root" to resourceRoot.resolve("a/../keystore.p12"),
        )
    for ((key, path) in clashes) {
      val e =
          assertFailsWith<ConfigurationException>("$key $path") {
            parse("secrets.keystorePath" to "$path", "secrets.password" to "p")
          }
      assertTrue(e.message!!.contains("secrets.keystorePath"), e.message)
      assertTrue(e.message!!.contains(key), "$key missing from: ${e.message}")
      assertFalse(e.message!!.contains(path.toString()), "no path in: ${e.message}")
    }
  }

  @Test
  fun `all the locations a keystore is in are listed, and a link into one is followed`() {
    val real = Files.createDirectories(resourceRoot)
    val link = Files.createSymbolicLink(dir.resolve("config-link"), real)

    val e =
        assertFailsWith<ConfigurationException> {
          parse(
              "secrets.keystorePath" to "${link.resolve("keystore.p12")}",
              "secrets.password" to "p",
          )
        }

    assertTrue(e.message!!.contains("resources.root"), e.message)
  }

  @Test
  fun `a keystore beside those directories, not in them, is fine`() {
    val secrets = parse("secrets.keystorePath" to "$keystore", "secrets.password" to "p").secrets

    assertEquals(keystore, secrets!!.keystore)
  }

  @Test
  fun `the settings come from the environment variables the deployment guides name`() {
    val yaml =
        checkNotNull(javaClass.getResourceAsStream("/application.yaml")).use {
          it.readBytes().decodeToString()
        }

    val block = yaml.substringAfter("\nsecrets:").lines().drop(1).takeWhile { it.startsWith("  ") }
    assertEquals(
        listOf(
            """keystorePath: "${'$'}RUNLINE_KEYSTORE_PATH:"""",
            """passwordFile: "${'$'}RUNLINE_KEYSTORE_PASSWORD_FILE:"""",
            """password: "${'$'}RUNLINE_KEYSTORE_PASSWORD:"""",
        ),
        block.map { it.trim() },
    )
  }
}
