package dev.lawlan.runline.accessors.openai

/**
 * What may be a header of an `openai-compatible` resource, and which headers of an answer are
 * credentials (ADR-019 decision 4): the same words decide both, so nothing that is named like a
 * credential goes out as an extra header or comes back to a pipeline.
 */
internal object HeaderRules {
  /** Words that make a header name a credential, in any case and anywhere in the name. */
  private val CREDENTIAL_WORDS = listOf("auth", "key", "token", "secret", "cookie")

  /** Headers the Engine sets or that frame the request: an administrator may not set them. */
  private val ENGINE_MANAGED =
      setOf(
          "host",
          "content-length",
          "content-type",
          "accept",
          "transfer-encoding",
          "connection",
          "keep-alive",
          "proxy-connection",
          "upgrade",
          "expect",
          "te",
          "trailer",
          "openai-organization",
          "openai-project",
      )

  private val TOKEN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")

  /**
   * Whether [name] is a credential by its name: authorization, API keys, tokens, secrets, cookies.
   */
  fun isCredentialName(name: String): Boolean {
    val lower = name.lowercase()
    return CREDENTIAL_WORDS.any { lower.contains(it) }
  }

  /** Whether an administrator may add a header of this name. */
  fun isAllowedExtraName(name: String): Boolean =
      TOKEN.matches(name) && !isCredentialName(name) && name.lowercase() !in ENGINE_MANAGED

  /** Visible ASCII and spaces and tabs inside: no line break, no other control character. */
  fun isValidValue(value: String): Boolean =
      value.length <= MAX_VALUE &&
          value.all { it == '\t' || it.code in 0x20..0x7e } &&
          value == value.trim()

  private const val MAX_VALUE = 1024
}
