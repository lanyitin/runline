package dev.lawlan.runline.engine.oidc

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The identifier of a session and the token of its CSRF check are random, 256 bits, in base64url:
 * not guessable, and safe in a cookie and a header. The database holds only the SHA-256 of the
 * identifier, so a copy of it cannot be used to be anybody.
 */
object SessionIds {
  private val random = SecureRandom()

  fun newToken(): String {
    val bytes = ByteArray(32).also(random::nextBytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  }

  fun hash(identifier: String): ByteArray =
      MessageDigest.getInstance("SHA-256").digest(identifier.toByteArray(Charsets.UTF_8))
}
