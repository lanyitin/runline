package dev.lawlan.runline.engine.config

import io.ktor.server.config.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import kotlin.test.*

/** The settings of OIDC login (04-deployment): all or nothing, checked once at startup. */
class OidcSettingsTest {
  private val dir: Path = Files.createTempDirectory("oidc-settings")
  private val key = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })

  private val base =
      mapOf(
          "postgres.url" to "jdbc:postgresql://db/runline",
          "postgres.user" to "runline",
          "postgres.password" to "pw",
          "auth.tokens" to "root:admin:tok-root-5678",
          "workspace.sharedRoot" to dir.resolve("shared").toString(),
          "workspace.runRoot" to dir.resolve("runs").toString(),
          "workspace.maxBytes" to "1048576",
          "workspace.failedRunRetentionSeconds" to "3600",
          "runs.maxConcurrent" to "4",
          "runs.runtimeDir" to "/opt/runline/run-runtime",
          "resources.root" to dir.resolve("resources").toString(),
      )

  private val oidc =
      mapOf(
          "oidc.issuer" to "https://auth.example.test/application/o/runline/",
          "oidc.clientId" to "runline-client",
          "oidc.clientSecret" to "client-secret-value-1",
          "oidc.publicBaseUrl" to "https://runline.example.test",
          "session.encryptionKey" to key,
      )

  private fun parse(vararg settings: Pair<String, String>) =
      EngineConfig.from(MapApplicationConfig(*((base + oidc) + settings).toList().toTypedArray()))

  private fun parseWithoutOidc(vararg settings: Pair<String, String>) =
      EngineConfig.from(MapApplicationConfig(*(base + settings).toList().toTypedArray()))

  private fun problems(vararg settings: Pair<String, String>): String =
      assertFailsWith<ConfigurationException> { parse(*settings) }.message!!

  private fun problemsWithoutOidc(vararg settings: Pair<String, String>): String =
      assertFailsWith<ConfigurationException> { parseWithoutOidc(*settings) }.message!!

  private fun file(name: String, content: String): Path =
      Files.writeString(dir.resolve(name), content)

  @Test
  fun `none of the settings given leaves login off`() {
    assertNull(parseWithoutOidc().oidc)
  }

  @Test
  fun `the required settings give a login with the documented defaults`() {
    val settings = parse().oidc!!

    assertEquals("https://auth.example.test/application/o/runline/", settings.issuer)
    assertEquals("runline-client", settings.clientId)
    assertEquals("client-secret-value-1", settings.clientSecret.reveal())
    assertEquals("https://runline.example.test", settings.publicBaseUrl)
    assertEquals("groups", settings.groupsClaim)
    assertEquals(200, settings.maxPendingLogins)
    assertEquals("runline-admin", settings.adminGroup)
    assertEquals("runline-developer", settings.developerGroup)
    assertEquals(Duration.ofSeconds(28800), settings.session.idle)
    assertEquals(Duration.ofSeconds(604800), settings.session.absolute)
    assertEquals(Duration.ofSeconds(60), settings.session.revalidate)
    assertEquals(Duration.ofSeconds(900), settings.session.revalidateGrace)
    assertTrue(settings.secureCookie)
    assertEquals(
        "https://runline.example.test/api/v1/auth/oidc/callback",
        settings.redirectUri,
    )
  }

  @Test
  fun `everything can be set, and a trailing slash of the public address is dropped`() {
    val settings =
        parse(
                "oidc.groupsClaim" to "roles",
                "oidc.maxPendingLogins" to "50",
                "oidc.adminGroup" to "ops",
                "oidc.developerGroup" to "devs",
                "session.idleSeconds" to "600",
                "session.absoluteSeconds" to "3600",
                "session.revalidateSeconds" to "10",
                "session.revalidateGraceSeconds" to "0",
                "oidc.publicBaseUrl" to "http://localhost:8080/",
            )
            .oidc!!

    assertEquals("roles", settings.groupsClaim)
    assertEquals(50, settings.maxPendingLogins)
    assertEquals("ops", settings.adminGroup)
    assertEquals("devs", settings.developerGroup)
    assertEquals(Duration.ofSeconds(600), settings.session.idle)
    assertEquals(Duration.ofSeconds(3600), settings.session.absolute)
    assertEquals(Duration.ofSeconds(10), settings.session.revalidate)
    assertEquals(Duration.ZERO, settings.session.revalidateGrace)
    assertEquals("http://localhost:8080", settings.publicBaseUrl)
    assertFalse(settings.secureCookie)
    assertEquals("http://localhost:8080/api/v1/auth/oidc/callback", settings.redirectUri)
  }

  @Test
  fun `a part of the settings stops the start and says every one that is missing`() {
    val message = problemsWithoutOidc("oidc.issuer" to "https://auth.example.test/")

    for (key in
        listOf(
            "oidc.clientId",
            "oidc.clientSecret",
            "oidc.publicBaseUrl",
            "session.encryptionKey",
        )) {
      assertTrue(message.contains(key), "$key in: $message")
    }
    assertFalse(message.contains("oidc.issuer is required"), message)
  }

  @Test
  fun `a setting that has a default still switches login on`() {
    assertTrue(problemsWithoutOidc("oidc.adminGroup" to "ops").contains("oidc.issuer"))
    assertTrue(problemsWithoutOidc("session.idleSeconds" to "60").contains("oidc.issuer"))
  }

  @Test
  fun `the client secret comes from a file or a variable, not both, and the file is read`() {
    val fromFile =
        parse(
                "oidc.clientSecret" to "",
                "oidc.clientSecretFile" to file("secret", "from-file-secret\n").toString(),
            )
            .oidc!!
    assertEquals("from-file-secret", fromFile.clientSecret.reveal())

    val both =
        problems(
            "oidc.clientSecretFile" to file("secret2", "from-file-secret\n").toString(),
        )
    assertTrue(both.contains("oidc.clientSecret and oidc.clientSecretFile"), both)
    assertFalse(both.contains("from-file-secret") || both.contains("client-secret-value-1"), both)

    val unreadable =
        problems(
            "oidc.clientSecret" to "",
            "oidc.clientSecretFile" to dir.resolve("missing").toString(),
        )
    assertTrue(unreadable.contains("oidc.clientSecretFile"), unreadable)
  }

  @Test
  fun `the encryption key is 32 bytes in base64, from a file or a variable, not both`() {
    for (bad in listOf("not base64 !!", Base64.getEncoder().encodeToString(ByteArray(16)))) {
      val message = problems("session.encryptionKey" to bad)
      assertTrue(message.contains("session.encryptionKey"), message)
      assertFalse(message.contains(bad), message)
    }

    val fromFile =
        parse(
                "session.encryptionKey" to "",
                "session.encryptionKeyFile" to file("key", "$key\n").toString(),
            )
            .oidc!!
    assertEquals(key, fromFile.session.encryptionKey.reveal())

    val both = problems("session.encryptionKeyFile" to file("key2", "$key\n").toString())
    assertTrue(both.contains("session.encryptionKey and session.encryptionKeyFile"), both)
    assertFalse(both.contains(key), both)
  }

  @Test
  fun `the two groups are different, and an idle time is not above the absolute time`() {
    val sameGroup = problems("oidc.adminGroup" to "team", "oidc.developerGroup" to "team")
    assertTrue(sameGroup.contains("oidc.adminGroup and oidc.developerGroup"), sameGroup)

    val idle = problems("session.idleSeconds" to "7200", "session.absoluteSeconds" to "3600")
    assertTrue(idle.contains("session.idleSeconds"), idle)
  }

  @Test
  fun `the numbers are in their ranges and the addresses are web addresses`() {
    assertTrue(problems("session.revalidateSeconds" to "9").contains("session.revalidateSeconds"))
    assertTrue(problems("session.revalidateGraceSeconds" to "-1").contains("revalidateGrace"))
    assertTrue(problems("session.idleSeconds" to "0").contains("session.idleSeconds"))
    assertTrue(problems("oidc.maxPendingLogins" to "0").contains("oidc.maxPendingLogins"))
    assertTrue(problems("oidc.issuer" to "ftp://auth").contains("oidc.issuer"))
    assertTrue(problems("oidc.issuer" to "auth.example.test").contains("oidc.issuer"))
    assertTrue(problems("oidc.publicBaseUrl" to "runline").contains("oidc.publicBaseUrl"))
    assertTrue(
        problems("oidc.publicBaseUrl" to "https://runline.example.test/app")
            .contains("oidc.publicBaseUrl")
    )
  }

  @Test
  fun `a message never holds a secret`() {
    val message =
        problems(
            "oidc.issuer" to "",
            "session.encryptionKey" to "bad key with secret-material",
        )

    assertFalse(message.contains("client-secret-value-1"), message)
    assertFalse(message.contains("secret-material"), message)
    assertFalse(parse().oidc.toString().contains("client-secret-value-1"))
    assertFalse(parse().oidc.toString().contains(key))
  }

  @Test
  fun `the clean-up of expired sessions uses the idle time of the sessions`() {
    assertEquals(
        Duration.ofSeconds(600),
        parse("session.idleSeconds" to "600").retention.sessionIdle,
    )
    assertEquals(Duration.ofSeconds(28800), parseWithoutOidc().retention.sessionIdle)
  }
}
