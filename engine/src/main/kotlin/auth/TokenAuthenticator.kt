package dev.lawlan.runline.engine.auth

import dev.lawlan.runline.engine.config.ApiToken
import java.security.MessageDigest

/** Who a caller is: the name recorded as uploader and in audit fields, and the role held. */
data class ApiIdentity(val name: String, val role: Role)

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
