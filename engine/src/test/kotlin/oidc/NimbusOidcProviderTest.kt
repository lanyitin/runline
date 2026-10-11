package dev.lawlan.runline.engine.oidc

import dev.lawlan.runline.engine.config.OidcSettings
import dev.lawlan.runline.engine.config.SessionSettings
import dev.lawlan.runline.engine.secret.SecretValue
import dev.lawlan.runline.engine.support.KeycloakTestProvider
import dev.lawlan.runline.engine.support.ProviderBrowser
import java.net.URI
import java.net.URLDecoder
import java.time.Duration
import java.util.UUID
import kotlin.test.*

/**
 * The Engine's part of the protocol against a real Keycloak (WI-1200, 05-ipc): the request that
 * sends a person to sign in, and the redemption of the code with the checks of the ID token.
 */
class NimbusOidcProviderTest {
  private fun settings(
      issuer: String = KeycloakTestProvider.issuer,
      secret: String = KeycloakTestProvider.CLIENT_SECRET,
      groupsClaim: String = "groups",
  ) =
      OidcSettings(
          issuer = issuer,
          clientId = KeycloakTestProvider.CLIENT_ID,
          clientSecret = SecretValue(secret),
          publicBaseUrl = KeycloakTestProvider.PUBLIC_BASE_URL,
          groupsClaim = groupsClaim,
          adminGroup = "runline-admin",
          developerGroup = "runline-developer",
          session =
              SessionSettings(
                  Duration.ofHours(8),
                  Duration.ofDays(7),
                  Duration.ofSeconds(60),
                  Duration.ofMinutes(15),
                  SecretValue("unused"),
              ),
      )

  private val provider = NimbusOidcProvider(settings())

  private class Attempt(val state: String, val nonce: String, val verifier: String)

  private fun attempt() =
      Attempt(
          "state-" + UUID.randomUUID(),
          "nonce-" + UUID.randomUUID(),
          // 43 to 128 characters of the unreserved set (RFC 7636).
          (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace("-", ""),
      )

  private fun query(uri: URI): Map<String, String> =
      uri.rawQuery.split('&').associate {
        val (k, v) = it.split('=', limit = 2)
        k to URLDecoder.decode(v, Charsets.UTF_8)
      }

  /** Signs in at the real provider and returns the code it hands back. */
  private fun codeFor(
      user: dev.lawlan.runline.engine.support.ProviderUser,
      attempt: Attempt,
      settings: OidcProvider = provider,
  ): String {
    val back =
        ProviderBrowser()
            .signIn(
                settings.authorizationUri(attempt.state, attempt.nonce, attempt.verifier),
                user.username,
                user.password,
            )
    val parameters = query(back)
    assertEquals(attempt.state, parameters["state"])
    return assertNotNull(parameters["code"], back.toString())
  }

  private fun failure(block: () -> Unit): OidcFailure = assertFailsWith<OidcFailure> { block() }

  @Test
  fun `the person is sent to the authorization page of the provider with PKCE and no secret`() {
    val a = attempt()
    val uri = provider.authorizationUri(a.state, a.nonce, a.verifier)
    val parameters = query(uri)

    assertTrue(
        uri.toString().startsWith("${KeycloakTestProvider.issuer}/protocol/openid-connect/auth?")
    )
    assertEquals("code", parameters["response_type"])
    assertEquals(KeycloakTestProvider.CLIENT_ID, parameters["client_id"])
    assertEquals(
        "${KeycloakTestProvider.PUBLIC_BASE_URL}/api/v1/auth/oidc/callback",
        parameters["redirect_uri"],
    )
    assertEquals(a.state, parameters["state"])
    assertEquals(a.nonce, parameters["nonce"])
    assertEquals("S256", parameters["code_challenge_method"])
    assertNotNull(parameters["code_challenge"])
    assertEquals(
        setOf("openid", "profile", "offline_access"),
        parameters["scope"]!!.split(' ').toSet(),
    )
    // What proves the Engine is the client never travels through the browser.
    assertFalse(uri.toString().contains(a.verifier))
    assertFalse(uri.toString().contains(KeycloakTestProvider.CLIENT_SECRET))
  }

  @Test
  fun `a code is redeemed for who the person is, their groups and a refresh token`() {
    val user = KeycloakTestProvider.createUser(listOf("runline-admin", "staff"))
    val a = attempt()

    val login = provider.redeem(codeFor(user, a), a.verifier, a.nonce)

    assertEquals(KeycloakTestProvider.subjectOf(user), login.subject)
    assertEquals(user.username, login.username)
    assertEquals("First ${user.username}", login.displayName)
    assertEquals(setOf("runline-admin", "staff"), login.groups!!.toSet())
    assertTrue(!login.refreshToken.isNullOrEmpty())
  }

  @Test
  fun `a person in no group has an empty groups claim, and another claim name has none`() {
    val user = KeycloakTestProvider.createUser(emptyList())
    val a = attempt()
    val code = codeFor(user, a)
    assertEquals(emptyList(), provider.redeem(code, a.verifier, a.nonce).groups.orEmpty())

    val other = KeycloakTestProvider.createUser(listOf("runline-admin"))
    val b = attempt()
    val login =
        NimbusOidcProvider(settings(groupsClaim = "roles"))
            .redeem(codeFor(other, b), b.verifier, b.nonce)
    assertNull(login.groups)
  }

  @Test
  fun `an ID token for another sign-in is refused, so is a wrong verifier`() {
    val user = KeycloakTestProvider.createUser(listOf("runline-developer"))

    val a = attempt()
    assertEquals(
        OidcFailure.Kind.ID_TOKEN_INVALID,
        failure { provider.redeem(codeFor(user, a), a.verifier, "another-nonce") }.kind,
    )

    val b = attempt()
    assertEquals(
        OidcFailure.Kind.REJECTED,
        failure { provider.redeem(codeFor(user, b), b.verifier + "x", b.nonce) }.kind,
    )
  }

  @Test
  fun `a code works once`() {
    val user = KeycloakTestProvider.createUser(listOf("runline-developer"))
    val a = attempt()
    val code = codeFor(user, a)
    provider.redeem(code, a.verifier, a.nonce)

    assertEquals(
        OidcFailure.Kind.REJECTED,
        failure { provider.redeem(code, a.verifier, a.nonce) }.kind,
    )
    assertEquals(
        OidcFailure.Kind.REJECTED,
        failure { provider.redeem("not-a-code", a.verifier, a.nonce) }.kind,
    )
  }

  @Test
  fun `a wrong client secret is refused by the provider`() {
    val user = KeycloakTestProvider.createUser(listOf("runline-developer"))
    val a = attempt()
    val code = codeFor(user, a)

    assertEquals(
        OidcFailure.Kind.REJECTED,
        failure {
          NimbusOidcProvider(settings(secret = "not-the-secret")).redeem(code, a.verifier, a.nonce)
        }
            .kind,
    )
  }

  @Test
  fun `a provider that cannot be reached is unavailable, for the page and for the code`() {
    val gone = NimbusOidcProvider(settings(issuer = "http://localhost:1/realms/runline"))
    val a = attempt()

    assertEquals(
        OidcFailure.Kind.UNAVAILABLE,
        failure { gone.authorizationUri(a.state, a.nonce, a.verifier) }.kind,
    )
    assertEquals(
        OidcFailure.Kind.UNAVAILABLE,
        failure { gone.redeem("code", a.verifier, a.nonce) }.kind,
    )
  }

  @Test
  fun `a provider that states another issuer than the configured one is not used`() {
    // The provider states the issuer without the slash; the configured one is not that one.
    val misnamed = NimbusOidcProvider(settings(issuer = KeycloakTestProvider.issuer + "/"))
    val a = attempt()

    assertEquals(
        OidcFailure.Kind.UNAVAILABLE,
        failure { misnamed.authorizationUri(a.state, a.nonce, a.verifier) }.kind,
    )
  }

  @Test
  fun `a failure says its category and nothing of the exchange`() {
    val a = attempt()
    val e = failure { provider.redeem("secret-looking-code", a.verifier, a.nonce) }

    assertEquals("REJECTED", e.message)
    assertFalse(e.toString().contains("secret-looking-code"))
    assertFalse(e.toString().contains(KeycloakTestProvider.CLIENT_SECRET))
  }
}
