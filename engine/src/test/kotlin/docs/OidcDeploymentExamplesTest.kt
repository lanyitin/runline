package dev.lawlan.runline.engine.docs

import dev.lawlan.runline.engine.oidc.LoginError
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * What an operator is handed to switch OIDC login on (WI-1203): the example environment files of
 * Docker and systemd name every setting, leave login off and hold placeholders only, and the manual
 * for the provider answers every failure code the Engine can send the browser back with.
 */
class OidcDeploymentExamplesTest {
  private val deploy = Path.of(System.getProperty("runline.deployDir"))

  private val settings =
      listOf(
          "RUNLINE_OIDC_ISSUER",
          "RUNLINE_OIDC_CLIENT_ID",
          "RUNLINE_OIDC_CLIENT_SECRET",
          "RUNLINE_OIDC_CLIENT_SECRET_FILE",
          "RUNLINE_PUBLIC_BASE_URL",
          "RUNLINE_OIDC_GROUPS_CLAIM",
          "RUNLINE_OIDC_ADMIN_GROUP",
          "RUNLINE_OIDC_DEVELOPER_GROUP",
          "RUNLINE_OIDC_MAX_PENDING_LOGINS",
          "RUNLINE_SESSION_IDLE_SECONDS",
          "RUNLINE_SESSION_ABSOLUTE_SECONDS",
          "RUNLINE_SESSION_REVALIDATE_SECONDS",
          "RUNLINE_SESSION_REVALIDATE_GRACE_SECONDS",
          "RUNLINE_SESSION_ENCRYPTION_KEY",
          "RUNLINE_SESSION_ENCRYPTION_KEY_FILE",
      )

  private val examples = listOf("docker/.env.example", "systemd/runline.env.example")

  private fun lines(example: String) = Files.readAllLines(deploy.resolve(example))

  /** The value of [variable] where an example gives it, as a line or a commented-out line. */
  private fun given(example: String, variable: String): String? =
      lines(example).firstNotNullOfOrNull {
        Regex("^#? ?$variable=(.*)$").find(it)?.groupValues?.get(1)
      }

  @Test
  fun `each example names every setting of the login`() {
    for (example in examples) for (variable in settings) {
      assertNotNull(given(example, variable), "$example does not name $variable")
    }
  }

  @Test
  fun `login is off in the examples, since none of the settings is active`() {
    for (example in examples) for (variable in settings) {
      val active = lines(example).any { it.startsWith("$variable=") }
      assertFalse(active, "$example switches login on with $variable")
    }
  }

  @Test
  fun `the secret and the key are placeholders, not values`() {
    val secrets = listOf("RUNLINE_OIDC_CLIENT_SECRET", "RUNLINE_SESSION_ENCRYPTION_KEY")
    for (example in examples) for (variable in secrets) {
      assertContains(given(example, variable)!!, "change-me", message = "$example $variable")
    }
  }

  @Test
  fun `the examples say the settings that are optional with the defaults of the code`() {
    for (example in examples) {
      assertEquals("groups", given(example, "RUNLINE_OIDC_GROUPS_CLAIM"), example)
      assertEquals("runline-admin", given(example, "RUNLINE_OIDC_ADMIN_GROUP"), example)
      assertEquals("runline-developer", given(example, "RUNLINE_OIDC_DEVELOPER_GROUP"), example)
      assertEquals("28800", given(example, "RUNLINE_SESSION_IDLE_SECONDS"), example)
      assertEquals("604800", given(example, "RUNLINE_SESSION_ABSOLUTE_SECONDS"), example)
      assertEquals("60", given(example, "RUNLINE_SESSION_REVALIDATE_SECONDS"), example)
      assertEquals("900", given(example, "RUNLINE_SESSION_REVALIDATE_GRACE_SECONDS"), example)
    }
  }

  private val manual by lazy { Files.readString(deploy.resolve("authentik.md")) }

  @Test
  fun `the manual answers each failure code the browser can be sent back with`() {
    for (error in LoginError.entries) {
      assertContains(
          manual,
          "`${error.code}`",
          message = "the manual says nothing of ${error.code}",
      )
    }
  }

  @Test
  fun `the manual covers the settings the provider must have`() {
    for (topic in
        listOf(
            "RS256",
            "offline_access",
            "preferred_username",
            "redirect URI",
            "runline-admin",
            "runline-developer",
            "RUNLINE_SESSION_ABSOLUTE_SECONDS",
        )) {
      assertContains(manual, topic)
    }
  }
}
