package dev.lawlan.runline.engine.oidc

import java.net.URI

/** What the provider says about a person who has signed in there, from a verified ID token. */
class ProviderLogin(
    val subject: String,
    /** The `preferred_username`; null when the token has none. */
    val username: String?,
    val displayName: String?,
    /** The groups claim; null when the token has none. */
    val groups: List<String>?,
    /** Null when the provider gave none. */
    val refreshToken: String?,
) {
  // The refresh token is a credential; no text form shows it.
  override fun toString() = "ProviderLogin(subject=$subject)"
}

/** What the provider says about a person when their session is checked again (WI-1205). */
class RefreshedLogin(
    /** The groups claim of the new ID token; null when it has none. */
    val groups: List<String>?,
    /** The refresh token to keep from now on, when the provider rotated it; null when unchanged. */
    val refreshToken: String?,
) {
  override fun toString() = "RefreshedLogin()"
}

/** Why the provider could not be used. The message is the category and nothing else. */
class OidcFailure(
    val kind: Kind,
    cause: Throwable? = null,
    /** For [Kind.UNACCEPTED_ALGORITHM]: the name of the algorithm, which is public. */
    val algorithm: String? = null,
) : RuntimeException(kind.name, cause) {
  enum class Kind {
    /** The provider could not be reached, or what it published could not be used. */
    UNAVAILABLE,
    /** The provider refused the code (used, expired, wrong verifier or client). */
    REJECTED,
    /** The ID token is not a valid one for this client, this sign-in and this provider. */
    ID_TOKEN_INVALID,
    /** The ID token is signed with an algorithm that is not accepted: only RS256 is (ADR-030). */
    UNACCEPTED_ALGORITHM,
  }
}

/** The part of an OpenID Connect provider the Engine uses to sign people in (ADR-029). */
interface OidcProvider {
  /** Where to send the person to sign in: the provider's authorization page for this sign-in. */
  fun authorizationUri(state: String, nonce: String, codeVerifier: String): URI

  /**
   * Redeems [code] once for the person's tokens and verifies the ID token (signature, issuer,
   * audience, expiry and [nonce]). The code is sent once; no failure is retried, because a code can
   * only be used once.
   *
   * @throws OidcFailure
   */
  fun redeem(code: String, codeVerifier: String, nonce: String): ProviderLogin

  /**
   * Asks the provider again about the person who holds [refreshToken], and verifies the new ID
   * token (signature, algorithm, issuer, audience, expiry). The token is sent once; a failure is
   * not retried, because a provider that rotates tokens has used it up.
   * [OidcFailure.Kind.UNAVAILABLE] is the one failure that says nothing about the person; every
   * other kind means they are not to be trusted any more.
   *
   * @throws OidcFailure
   */
  fun refresh(refreshToken: String): RefreshedLogin
}
