package dev.lawlan.runline.engine.oidc

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Seals the refresh token kept with a session (ADR-029, 9): AES-256-GCM with a new random nonce for
 * every seal, so the database holds nothing it could read or compare. The sealed form is the nonce
 * followed by the ciphertext. [context] is authenticated with it, so a sealed token cannot be moved
 * to another session. The key never leaves this object; nothing here says it, the text or the
 * reason in a message.
 */
class SessionCipher(key: ByteArray) {
  private val key = SecretKeySpec(key.also { require(it.size == KEY_BYTES) { "key size" } }, "AES")
  private val random = SecureRandom()

  fun seal(plaintext: String, context: ByteArray): ByteArray {
    val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
    val cipher = cipher(Cipher.ENCRYPT_MODE, nonce, context)
    return nonce + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
  }

  /**
   * The text that was sealed, or null when [sealed] is not a seal made with this key and [context].
   */
  fun open(sealed: ByteArray, context: ByteArray): String? {
    if (sealed.size < NONCE_BYTES + TAG_BYTES) return null
    return try {
      val cipher = cipher(Cipher.DECRYPT_MODE, sealed.copyOfRange(0, NONCE_BYTES), context)
      String(cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES), Charsets.UTF_8)
    } catch (e: GeneralSecurityException) {
      null
    }
  }

  private fun cipher(mode: Int, nonce: ByteArray, context: ByteArray): Cipher =
      Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, key, GCMParameterSpec(TAG_BYTES * 8, nonce))
        updateAAD(context)
      }

  companion object {
    const val KEY_BYTES = 32
    private const val NONCE_BYTES = 12
    private const val TAG_BYTES = 16
  }
}
