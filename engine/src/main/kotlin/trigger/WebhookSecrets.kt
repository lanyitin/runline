package dev.lawlan.runline.engine.trigger

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The secret of a webhook trigger (ADR-005). A secret is 256 bits from a secure random generator,
 * which is why a plain SHA-256 is enough to store: there is nothing to guess and nothing to work
 * backwards from. Only the hash is kept; a secret cannot be recovered, only replaced.
 */
object WebhookSecrets {
  private const val SECRET_BYTES = 32
  private val random = SecureRandom()

  fun generate(): String {
    val bytes = ByteArray(SECRET_BYTES).also(random::nextBytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  }

  /** The stored form of [secret]: lower case hex of its SHA-256. */
  fun hash(secret: String): String =
      MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8)).toHex()

  /**
   * Whether [presented] is the secret behind [storedHash]. Compares digests with a constant-time
   * equality check, so the time taken does not tell how much of a secret was right.
   */
  fun matches(presented: String, storedHash: String): Boolean =
      MessageDigest.isEqual(
          hash(presented).toByteArray(Charsets.US_ASCII),
          storedHash.toByteArray(Charsets.US_ASCII),
      )

  private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
