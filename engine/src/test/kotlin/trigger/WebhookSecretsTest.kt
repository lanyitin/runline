package dev.lawlan.runline.engine.trigger

import kotlin.test.*

class WebhookSecretsTest {
  @Test
  fun `generated secrets are long, url safe and never repeat`() {
    val secrets = (1..1000).map { WebhookSecrets.generate() }

    assertEquals(1000, secrets.toSet().size)
    secrets.forEach {
      // 256 bits as unpadded base64url.
      assertEquals(43, it.length, it)
      assertTrue(it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' }, it)
    }
  }

  @Test
  fun `the stored form is the SHA-256 in hex and does not contain the secret`() {
    // SHA-256 of "abc", a published test vector.
    assertEquals(
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        WebhookSecrets.hash("abc"),
    )
    val secret = WebhookSecrets.generate()
    assertFalse(WebhookSecrets.hash(secret).contains(secret))
  }

  @Test
  fun `a secret matches its own hash and nothing else`() {
    val secret = WebhookSecrets.generate()
    val hash = WebhookSecrets.hash(secret)

    assertTrue(WebhookSecrets.matches(secret, hash))
    assertFalse(WebhookSecrets.matches(WebhookSecrets.generate(), hash))
    assertFalse(WebhookSecrets.matches(secret.dropLast(1), hash))
    assertFalse(WebhookSecrets.matches("", hash))
    assertFalse(WebhookSecrets.matches(hash, hash), "the hash is not itself a secret")
  }
}
