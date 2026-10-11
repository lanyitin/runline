package dev.lawlan.runline.engine.oidc

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.RemoteKeySourceException
import com.nimbusds.jose.proc.BadJOSEException
import com.nimbusds.jose.util.DefaultResourceRetriever
import com.nimbusds.oauth2.sdk.AuthorizationCode
import com.nimbusds.oauth2.sdk.AuthorizationCodeGrant
import com.nimbusds.oauth2.sdk.AuthorizationGrant
import com.nimbusds.oauth2.sdk.RefreshTokenGrant
import com.nimbusds.oauth2.sdk.ResponseType
import com.nimbusds.oauth2.sdk.Scope
import com.nimbusds.oauth2.sdk.TokenRequest
import com.nimbusds.oauth2.sdk.auth.ClientSecretBasic
import com.nimbusds.oauth2.sdk.auth.Secret
import com.nimbusds.oauth2.sdk.id.ClientID
import com.nimbusds.oauth2.sdk.id.Issuer
import com.nimbusds.oauth2.sdk.id.State
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier
import com.nimbusds.oauth2.sdk.token.RefreshToken
import com.nimbusds.openid.connect.sdk.AuthenticationRequest
import com.nimbusds.openid.connect.sdk.Nonce
import com.nimbusds.openid.connect.sdk.OIDCTokenResponse
import com.nimbusds.openid.connect.sdk.OIDCTokenResponseParser
import com.nimbusds.openid.connect.sdk.claims.IDTokenClaimsSet
import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata
import com.nimbusds.openid.connect.sdk.token.OIDCTokens
import com.nimbusds.openid.connect.sdk.validators.IDTokenValidator
import dev.lawlan.runline.engine.config.OidcSettings
import java.io.IOException
import java.net.URI
import java.net.URL
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * The provider reached through its discovery document (ADR-029): nothing is looked up at start; the
 * document and the keys are fetched the first time somebody signs in, kept for [DISCOVERY_TTL], and
 * fetched again after it or after a failure. Every call to the provider has a connect and a read
 * limit. The code is redeemed once and never retried. No message made here has a token, a code, the
 * verifier or the client secret in it.
 */
class NimbusOidcProvider(
    private val settings: OidcSettings,
    private val clock: Clock = Clock.systemUTC(),
) : OidcProvider {
  private class Discovered(
      val metadata: OIDCProviderMetadata,
      val validator: IDTokenValidator,
      val at: Instant,
  )

  @Volatile private var discovered: Discovered? = null

  override fun authorizationUri(state: String, nonce: String, codeVerifier: String): URI {
    val endpoint = discovery().metadata.authorizationEndpointURI
    return AuthenticationRequest.Builder(
            ResponseType.CODE,
            Scope("openid", "profile", "offline_access"),
            ClientID(settings.clientId),
            URI(settings.redirectUri),
        )
        .endpointURI(endpoint)
        .state(State(state))
        .nonce(Nonce(nonce))
        .codeChallenge(CodeVerifier(codeVerifier), CodeChallengeMethod.S256)
        .build()
        .toURI()
  }

  override fun redeem(code: String, codeVerifier: String, nonce: String): ProviderLogin {
    val found = discovery()
    val tokens = exchange(found.metadata, code, codeVerifier)
    val claims = verify(found.validator, tokens, nonce)
    return ProviderLogin(
        subject = claims.subject.value,
        username = claims.getStringClaim("preferred_username"),
        displayName = claims.getStringClaim("name"),
        groups = groups(claims),
        refreshToken = tokens.refreshToken?.value,
    )
  }

  override fun refresh(refreshToken: String): RefreshedLogin {
    val found = discovery()
    val tokens = send(found.metadata, RefreshTokenGrant(RefreshToken(refreshToken)))
    // The answer to a refresh has to carry an ID token: the groups are read from it, and without
    // them nothing can be said about the person.
    if (tokens.idToken == null) throw OidcFailure(OidcFailure.Kind.ID_TOKEN_INVALID)
    val claims = verify(found.validator, tokens, null)
    return RefreshedLogin(groups(claims), tokens.refreshToken?.value)
  }

  private fun exchange(metadata: OIDCProviderMetadata, code: String, verifier: String) =
      send(
          metadata,
          AuthorizationCodeGrant(
              AuthorizationCode(code),
              URI(settings.redirectUri),
              CodeVerifier(verifier),
          ),
      )

  // One request, sent once, whatever the grant: a code and a rotated refresh token can each be
  // used a single time, so a failure is never tried again.
  private fun send(metadata: OIDCProviderMetadata, grant: AuthorizationGrant): OIDCTokens {
    val request =
        TokenRequest.Builder(
                metadata.tokenEndpointURI,
                ClientSecretBasic(
                    ClientID(settings.clientId),
                    Secret(settings.clientSecret.reveal()),
                ),
                grant,
            )
            .build()
            .toHTTPRequest()
            .apply {
              connectTimeout = TIMEOUT_MILLIS
              readTimeout = TIMEOUT_MILLIS
            }
    val response =
        try {
          OIDCTokenResponseParser.parse(request.send())
        } catch (e: IOException) {
          throw OidcFailure(OidcFailure.Kind.UNAVAILABLE, e)
        } catch (e: com.nimbusds.oauth2.sdk.ParseException) {
          // Not a token response at all: a gateway's page, or a provider in trouble.
          throw OidcFailure(OidcFailure.Kind.UNAVAILABLE, e)
        }
    if (!response.indicatesSuccess()) {
      val status = response.toErrorResponse().errorObject?.httpStatusCode ?: 0
      throw OidcFailure(
          if (status >= 500) OidcFailure.Kind.UNAVAILABLE else OidcFailure.Kind.REJECTED
      )
    }
    return (response.toSuccessResponse() as OIDCTokenResponse).oidcTokens
  }

  private fun verify(
      validator: IDTokenValidator,
      tokens: OIDCTokens,
      nonce: String?,
  ): IDTokenClaimsSet {
    // Only an asymmetric signature is trusted (ADR-030): a token signed with the client secret, as
    // a provider does when it has no signing key chosen, proves nothing that every holder of the
    // secret could not forge. The header is read, not trusted: only the name of the algorithm,
    // which is public, is passed on.
    val algorithm = tokens.idToken.header.algorithm
    if (algorithm != JWSAlgorithm.RS256) {
      throw OidcFailure(OidcFailure.Kind.UNACCEPTED_ALGORITHM, algorithm = algorithm.name)
    }
    return try {
      validator.validate(tokens.idToken, nonce?.let(::Nonce))
    } catch (e: RemoteKeySourceException) {
      throw OidcFailure(OidcFailure.Kind.UNAVAILABLE, e)
    } catch (e: BadJOSEException) {
      throw OidcFailure(OidcFailure.Kind.ID_TOKEN_INVALID, e)
    } catch (e: JOSEException) {
      throw OidcFailure(OidcFailure.Kind.ID_TOKEN_INVALID, e)
    }
  }

  private fun groups(claims: IDTokenClaimsSet): List<String>? =
      when (val claim = claims.getClaim(settings.groupsClaim)) {
        null -> null
        is String -> listOf(claim)
        is Collection<*> -> claim.filterIsInstance<String>()
        else -> null
      }

  private fun discovery(): Discovered {
    val now = clock.instant()
    discovered
        ?.takeIf { Duration.between(it.at, now) < DISCOVERY_TTL }
        ?.let {
          return it
        }
    return synchronized(this) {
      discovered?.takeIf { Duration.between(it.at, now) < DISCOVERY_TTL }
          ?: fetch(now).also { discovered = it }
    }
  }

  private fun fetch(now: Instant): Discovered =
      try {
        val issuer = Issuer(settings.issuer)
        val metadata = OIDCProviderMetadata.resolve(issuer, TIMEOUT_MILLIS, TIMEOUT_MILLIS)
        // The issuer a provider states must be the one it is configured by (OIDC Discovery 4.3).
        if (metadata.issuer != issuer) throw OidcFailure(OidcFailure.Kind.UNAVAILABLE)
        Discovered(
            metadata,
            idTokenValidator(
                issuer,
                ClientID(settings.clientId),
                metadata.jwkSetURI.toURL(),
                CLOCK_SKEW,
            ),
            now,
        )
      } catch (e: OidcFailure) {
        throw e
      } catch (e: Exception) {
        // Unreachable, not a provider, or a document that cannot be used: tried again next time.
        throw OidcFailure(OidcFailure.Kind.UNAVAILABLE, e)
      }

  internal companion object {
    val DISCOVERY_TTL: Duration = Duration.ofHours(1)
    val CLOCK_SKEW: Duration = Duration.ofSeconds(60)
    const val TIMEOUT_MILLIS = 5_000
    const val MAX_DOCUMENT_BYTES = 256 * 1024
  }
}

/**
 * The checks of an ID token (ADR-029): the signature, by the keys the provider publishes at
 * [jwkSet] (RS256, the algorithm every provider must support), the issuer, the audience (the
 * client), the expiry with [clockSkew] to spare, and, when asked, the nonce.
 */
internal fun idTokenValidator(
    issuer: Issuer,
    clientId: ClientID,
    jwkSet: URL,
    clockSkew: Duration,
): IDTokenValidator =
    IDTokenValidator(
            issuer,
            clientId,
            JWSAlgorithm.RS256,
            jwkSet,
            DefaultResourceRetriever(
                NimbusOidcProvider.TIMEOUT_MILLIS,
                NimbusOidcProvider.TIMEOUT_MILLIS,
                NimbusOidcProvider.MAX_DOCUMENT_BYTES,
            ),
        )
        .apply { maxClockSkew = clockSkew.seconds.toInt() }
