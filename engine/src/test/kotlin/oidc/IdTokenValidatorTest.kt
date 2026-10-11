package dev.lawlan.runline.engine.oidc

import com.nimbusds.jwt.JWTParser
import com.nimbusds.oauth2.sdk.id.ClientID
import com.nimbusds.oauth2.sdk.id.Issuer
import dev.lawlan.runline.engine.support.KeycloakTestProvider
import java.net.URL
import java.time.Duration
import java.util.Base64
import kotlin.test.*

/**
 * The checks made of an ID token (WI-1200, 2): signature, issuer, audience and expiry, with a real
 * token that the real provider signed. The token is the provider's own, for another client of the
 * realm, so the client under test is the one that is wrong for it.
 */
class IdTokenValidatorTest {
  private val user = KeycloakTestProvider.createUser(listOf("runline-developer"))
  private val jwks = URL("${KeycloakTestProvider.issuer}/protocol/openid-connect/certs")

  private fun validator(
      issuer: String = KeycloakTestProvider.issuer,
      client: String = "runline-direct",
      skew: Duration = Duration.ofSeconds(60),
  ) = idTokenValidator(Issuer(issuer), ClientID(client), jwks, skew)

  private fun validates(
      validator: com.nimbusds.openid.connect.sdk.validators.IDTokenValidator,
      token: String,
  ) = validator.validate(JWTParser.parse(token), null)

  @Test
  fun `a token of the provider for the client is accepted`() {
    val claims = validates(validator(), KeycloakTestProvider.idTokenOfAnotherClient(user))

    assertEquals(KeycloakTestProvider.subjectOf(user), claims.subject.value)
  }

  @Test
  fun `a token for another client is refused`() {
    val token = KeycloakTestProvider.idTokenOfAnotherClient(user)

    assertFails { validates(validator(client = KeycloakTestProvider.CLIENT_ID), token) }
  }

  @Test
  fun `a token of another issuer is refused`() {
    val token = KeycloakTestProvider.idTokenOfAnotherClient(user)

    assertFails {
      validates(validator(issuer = "https://other.example.test/realms/runline"), token)
    }
  }

  @Test
  fun `a token whose content was changed is refused`() {
    val token = KeycloakTestProvider.idTokenOfAnotherClient(user)
    val (header, payload, signature) = token.split('.')
    val changed =
        String(Base64.getUrlDecoder().decode(payload)).replace(user.username, "someone-else")
    assertNotEquals(String(Base64.getUrlDecoder().decode(payload)), changed)
    val forged =
        "$header.${Base64.getUrlEncoder().withoutPadding().encodeToString(changed.toByteArray())}.$signature"

    assertFails { validates(validator(), forged) }
  }

  @Test
  fun `a token is refused once it expired`() {
    val token = KeycloakTestProvider.idTokenOfAnotherClient(user)
    validates(validator(skew = Duration.ZERO), token)

    // The token lives three seconds.
    Thread.sleep(4_000)

    assertFails { validates(validator(skew = Duration.ZERO), token) }
  }
}
