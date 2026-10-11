package dev.lawlan.runline.engine.config

import dev.lawlan.runline.engine.secret.SecretValue
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64

/** How long a session lives and how often it is checked with the provider (04-deployment). */
data class SessionSettings(
    val idle: Duration,
    val absolute: Duration,
    val revalidate: Duration,
    val revalidateGrace: Duration,
    /** The key that seals refresh tokens: 32 bytes in base64. Never said by [toString]. */
    val encryptionKey: SecretValue,
)

/**
 * What signing in with an OpenID Connect provider needs (ADR-029, 04-deployment). The Engine has
 * these only when login is configured; the secret and the key are [SecretValue]s, so a message that
 * is handed this object by mistake holds neither.
 */
data class OidcSettings(
    val issuer: String,
    val clientId: String,
    val clientSecret: SecretValue,
    /** The address people use for the Engine, without a trailing slash. */
    val publicBaseUrl: String,
    val groupsClaim: String,
    val adminGroup: String,
    val developerGroup: String,
    val session: SessionSettings,
    /** Most sign-ins under way at once; a new one beyond it is refused with a 429. */
    val maxPendingLogins: Int = 200,
) {
  /** Cookies are sent only over https when the public address is one. */
  val secureCookie: Boolean
    get() = publicBaseUrl.startsWith("https://")

  /** Where the provider sends the person back to; it must be registered with the provider. */
  val redirectUri: String
    get() = publicBaseUrl + CALLBACK_PATH

  companion object {
    const val CALLBACK_PATH = "/api/v1/auth/oidc/callback"

    private const val DEFAULT_GROUPS_CLAIM = "groups"
    private const val DEFAULT_ADMIN_GROUP = "runline-admin"
    private const val DEFAULT_DEVELOPER_GROUP = "runline-developer"
    private const val DEFAULT_MAX_PENDING_LOGINS = 200L
    private const val DEFAULT_IDLE_SECONDS = 28800L
    private const val DEFAULT_ABSOLUTE_SECONDS = 604800L
    private const val DEFAULT_REVALIDATE_SECONDS = 60L
    private const val MIN_REVALIDATE_SECONDS = 10L
    private const val DEFAULT_GRACE_SECONDS = 900L

    private val KEYS =
        listOf(
            "oidc.issuer",
            "oidc.clientId",
            "oidc.clientSecret",
            "oidc.clientSecretFile",
            "oidc.publicBaseUrl",
            "oidc.groupsClaim",
            "oidc.adminGroup",
            "oidc.developerGroup",
            "oidc.maxPendingLogins",
            "session.idleSeconds",
            "session.absoluteSeconds",
            "session.revalidateSeconds",
            "session.revalidateGraceSeconds",
            "session.encryptionKey",
            "session.encryptionKeyFile",
        )

    /**
     * The settings, or null when not one of them is given (login is off). Any one given switches
     * login on, so a part of them is a problem for each one that is missing. Problems are added to
     * [problems] and never say a value.
     */
    internal fun parse(text: (String) -> String?, problems: MutableList<String>): OidcSettings? {
      if (KEYS.all { text(it) == null }) return null

      fun required(key: String): String = text(key) ?: "".also { problems += "$key is required" }

      fun number(key: String, default: Long, min: Long): Long =
          text(key)?.let {
            it.toLongOrNull()?.takeIf { n -> n >= min }
                ?: default.also { problems += "$key must be a whole number of at least $min" }
          } ?: default

      val issuer = required("oidc.issuer")
      if (issuer.isNotEmpty() && webAddress(issuer) == null) {
        problems += "oidc.issuer must be an http or https address"
      }
      val publicBaseUrl = required("oidc.publicBaseUrl").trimEnd('/')
      if (publicBaseUrl.isNotEmpty() && webAddress(publicBaseUrl)?.rawPath?.isEmpty() != true) {
        problems += "oidc.publicBaseUrl must be an http or https address without a path"
      }
      val clientId = required("oidc.clientId")
      val clientSecret = sealedValue(text, "oidc.clientSecret", "oidc.clientSecretFile", problems)

      val adminGroup = text("oidc.adminGroup") ?: DEFAULT_ADMIN_GROUP
      val developerGroup = text("oidc.developerGroup") ?: DEFAULT_DEVELOPER_GROUP
      if (adminGroup == developerGroup) {
        problems += "oidc.adminGroup and oidc.developerGroup must differ"
      }

      val maxPendingLogins =
          number("oidc.maxPendingLogins", DEFAULT_MAX_PENDING_LOGINS, 1)
              .coerceAtMost(Int.MAX_VALUE.toLong())
              .toInt()
      val idle = number("session.idleSeconds", DEFAULT_IDLE_SECONDS, 1)
      val absolute = number("session.absoluteSeconds", DEFAULT_ABSOLUTE_SECONDS, 1)
      if (idle > absolute) {
        problems += "session.idleSeconds must not be longer than session.absoluteSeconds"
      }
      val revalidate =
          number("session.revalidateSeconds", DEFAULT_REVALIDATE_SECONDS, MIN_REVALIDATE_SECONDS)
      val grace = number("session.revalidateGraceSeconds", DEFAULT_GRACE_SECONDS, 0)
      val key = sealedValue(text, "session.encryptionKey", "session.encryptionKeyFile", problems)
      if (key != null && !isAes256Key(key.reveal())) {
        problems += "session.encryptionKey must be 32 bytes in base64"
      }

      if (clientSecret == null || key == null) return null
      return OidcSettings(
          issuer,
          clientId,
          clientSecret,
          publicBaseUrl,
          text("oidc.groupsClaim") ?: DEFAULT_GROUPS_CLAIM,
          adminGroup,
          developerGroup,
          SessionSettings(
              Duration.ofSeconds(idle),
              Duration.ofSeconds(absolute),
              Duration.ofSeconds(revalidate),
              Duration.ofSeconds(grace),
              key,
          ),
          maxPendingLogins,
      )
    }

    /** [uri] when it is an http or https address with a host; otherwise null. */
    private fun webAddress(uri: String): URI? =
        try {
          URI(uri).takeIf { it.scheme in setOf("http", "https") && !it.host.isNullOrEmpty() }
        } catch (e: URISyntaxException) {
          null
        }

    private fun isAes256Key(text: String): Boolean =
        try {
          Base64.getDecoder().decode(text).size == 32
        } catch (e: IllegalArgumentException) {
          false
        }

    /**
     * A value given directly or by a file whose first line it is, not both; null, with a problem
     * said, when it cannot be had. Neither the value nor the path is ever said.
     */
    private fun sealedValue(
        text: (String) -> String?,
        key: String,
        fileKey: String,
        problems: MutableList<String>,
    ): SecretValue? {
      val direct = text(key)
      val file = text(fileKey)
      return when {
        direct != null && file != null -> {
          problems += "$key and $fileKey must not both be set"
          null
        }
        direct != null -> SecretValue(direct)
        file != null ->
            try {
              Files.newBufferedReader(Path.of(file))
                  .use { it.readLine() }
                  ?.trim()
                  ?.takeIf { it.isNotEmpty() }
                  ?.let(::SecretValue) ?: null.also { problems += "$fileKey is empty" }
            } catch (e: IOException) {
              null.also { problems += "$fileKey cannot be read" }
            }
        else -> {
          problems += "$key or $fileKey is required"
          null
        }
      }
    }
  }
}
