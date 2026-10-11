package dev.lawlan.runline.engine.oidc

import java.security.SecureRandom
import kotlin.test.*

/** The refresh token is kept only in a form the database alone cannot read (ADR-029, 9). */
class SessionCipherTest {
  private fun key() = ByteArray(32).also { SecureRandom().nextBytes(it) }

  private val context = "session-1".toByteArray()

  @Test
  fun `what was sealed is opened with the same key and context`() {
    val cipher = SessionCipher(key())
    val sealed = cipher.seal("refresh-token-value", context)

    assertEquals("refresh-token-value", cipher.open(sealed, context))
  }

  @Test
  fun `the sealed form does not contain the text, and two seals of one text differ`() {
    val cipher = SessionCipher(key())
    val first = cipher.seal("refresh-token-value", context)
    val second = cipher.seal("refresh-token-value", context)

    assertFalse(String(first, Charsets.ISO_8859_1).contains("refresh-token-value"))
    assertFalse(first.contentEquals(second))
  }

  @Test
  fun `another key, another context or a changed byte opens nothing`() {
    val sealed = SessionCipher(key()).seal("refresh-token-value", context)
    assertNull(SessionCipher(key()).open(sealed, context))

    val cipher = SessionCipher(key())
    val mine = cipher.seal("refresh-token-value", context)
    assertNull(cipher.open(mine, "session-2".toByteArray()))
    val changed = mine.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
    assertNull(cipher.open(changed, context))
    assertNull(cipher.open(ByteArray(3), context))
  }

  @Test
  fun `a key is 32 bytes`() {
    assertFailsWith<IllegalArgumentException> { SessionCipher(ByteArray(16)) }
  }
}
