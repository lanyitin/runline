package dev.lawlan.runline.engine.auth

import dev.lawlan.runline.engine.config.ApiToken
import java.security.MessageDigest

/** How a caller proved who they are; it changes nothing about what they may do (ADR-029). */
enum class AuthSource(val wire: String) {
  /** A Bearer token of `API_TOKENS`. */
  TOKEN("token"),
  /** The session of a person who signed in with the OIDC provider. */
  OIDC("oidc"),
}

/**
 * Who a caller is: the name recorded as uploader and in audit fields, and the role held. Both
 * sources give the same thing, and every rule of visibility, ownership and role is made of the name
 * and the role alone; [source] is only said back to the caller.
 */
data class ApiIdentity(val name: String, val role: Role, val source: AuthSource = AuthSource.TOKEN)

/**
 * Turns a presented Bearer token into an identity. Role and identity semantics are fixed (ADR-012);
 * the way tokens are provisioned is replaceable by providing another implementation.
 */
fun interface TokenAuthenticator {
  /** The identity for [token], or null when the token is not valid. */
  fun authenticate(token: String): ApiIdentity?
}

/**
 * Tokens provided by configuration. Comparison is on SHA-256 digests with a constant-time equality
 * check, and every configured token is compared on every call, so response time reveals neither a
 * matching prefix nor the length of a token nor which entry matched.
 */
class ConfiguredTokenAuthenticator(tokens: List<ApiToken>) : TokenAuthenticator {
  private class Entry(val digest: ByteArray, val identity: ApiIdentity)

  private val entries = tokens.map { Entry(digest(it.secret), ApiIdentity(it.name, it.role)) }

  override fun authenticate(token: String): ApiIdentity? {
    val presented = digest(token)
    var match: ApiIdentity? = null
    for (entry in entries) {
      if (MessageDigest.isEqual(entry.digest, presented)) match = entry.identity
    }
    return match
  }

  private fun digest(value: String): ByteArray =
      MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
}
