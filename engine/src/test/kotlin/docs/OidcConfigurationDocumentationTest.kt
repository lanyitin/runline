package dev.lawlan.runline.engine.docs

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * The environment variables of OIDC login (WI-1200, 04-deployment of the login design) are the ones
 * the configuration file reads into the settings the code knows, and the README and the deployment
 * document of the Engine name every one of them with its default.
 */
class OidcConfigurationDocumentationTest {
  private val readme = Files.readString(Path.of(System.getProperty("runline.readme")))
  private val deployment =
      Files.readString(
          Path.of(System.getProperty("runline.apiDoc")).resolveSibling("04-deployment.md")
      )
  private val yaml =
      checkNotNull(javaClass.getResourceAsStream("/application.yaml")).use {
        it.readBytes().decodeToString()
      }

  private val expected =
      mapOf(
          "oidc.issuer" to "RUNLINE_OIDC_ISSUER",
          "oidc.clientId" to "RUNLINE_OIDC_CLIENT_ID",
          "oidc.clientSecret" to "RUNLINE_OIDC_CLIENT_SECRET",
          "oidc.clientSecretFile" to "RUNLINE_OIDC_CLIENT_SECRET_FILE",
          "oidc.publicBaseUrl" to "RUNLINE_PUBLIC_BASE_URL",
          "oidc.groupsClaim" to "RUNLINE_OIDC_GROUPS_CLAIM",
          "oidc.adminGroup" to "RUNLINE_OIDC_ADMIN_GROUP",
          "oidc.developerGroup" to "RUNLINE_OIDC_DEVELOPER_GROUP",
          "oidc.maxPendingLogins" to "RUNLINE_OIDC_MAX_PENDING_LOGINS",
          "session.idleSeconds" to "RUNLINE_SESSION_IDLE_SECONDS",
          "session.absoluteSeconds" to "RUNLINE_SESSION_ABSOLUTE_SECONDS",
          "session.revalidateSeconds" to "RUNLINE_SESSION_REVALIDATE_SECONDS",
          "session.revalidateGraceSeconds" to "RUNLINE_SESSION_REVALIDATE_GRACE_SECONDS",
          "session.encryptionKey" to "RUNLINE_SESSION_ENCRYPTION_KEY",
          "session.encryptionKeyFile" to "RUNLINE_SESSION_ENCRYPTION_KEY_FILE",
      )

  /** Setting name to variable, as the configuration file maps them in the two sections. */
  private fun mapped(): Map<String, String> {
    val result = mutableMapOf<String, String>()
    var section: String? = null
    for (line in yaml.lines()) {
      if (line.isNotEmpty() && !line.startsWith(" ") && !line.startsWith("#")) {
        section = line.substringBefore(':').takeIf { line.trimEnd().endsWith(":") }
      }
      val entry = Regex("""^  (\w+): "\$(\w+):"$""").find(line)
      if (entry != null && section in setOf("oidc", "session")) {
        result["$section.${entry.groupValues[1]}"] = entry.groupValues[2]
      }
    }
    return result
  }

  @Test
  fun `the configuration file reads each setting from its variable`() {
    assertEquals(expected, mapped())
  }

  @Test
  fun `the README has a row for every variable, with the defaults of the code`() {
    // A row may name several variables in its first cell (a file or a variable, as a group).
    fun row(variable: String) =
        readme.lines().firstOrNull {
          it.startsWith("|") && it.drop(1).substringBefore('|').contains("`$variable`")
        }
    for (variable in expected.values) assertNotNull(row(variable), variable)

    assertContains(row("RUNLINE_SESSION_IDLE_SECONDS")!!, "28800")
    assertContains(row("RUNLINE_SESSION_ABSOLUTE_SECONDS")!!, "604800")
    assertContains(row("RUNLINE_SESSION_REVALIDATE_SECONDS")!!, "60")
    assertContains(row("RUNLINE_SESSION_REVALIDATE_SECONDS")!!, "at least 10")
    assertContains(row("RUNLINE_SESSION_REVALIDATE_GRACE_SECONDS")!!, "900")
    assertContains(row("RUNLINE_OIDC_MAX_PENDING_LOGINS")!!, "200")
    assertContains(row("RUNLINE_OIDC_GROUPS_CLAIM")!!, "groups")
    assertContains(row("RUNLINE_OIDC_ADMIN_GROUP")!!, "runline-admin")
    assertContains(row("RUNLINE_OIDC_DEVELOPER_GROUP")!!, "runline-developer")
  }

  @Test
  fun `the deployment document names every variable`() {
    for (variable in expected.values) assertContains(deployment, "`$variable`")
  }
}
