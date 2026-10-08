package dev.lawlan.runline.accessors.tls

import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The keystore aliases of the certificates a resource's connections use (ADR-019 decision 12): the
 * trusted certificates, which replace the JVM's default trust when there are any, and the private
 * key entry of the client certificate. Aliases only, in the lower case the keystore tool writes;
 * what they hold is the keystore's.
 */
data class TlsAliases(val trustAliases: List<String>, val clientCertAlias: String?) {
  /** Whether the resource names no certificate at all. */
  val isEmpty: Boolean
    get() = trustAliases.isEmpty() && clientCertAlias == null

  /** The members of the settings, as they are stored; nothing when nothing is named. */
  fun writeTo(settings: MutableMap<String, JsonElement>) {
    if (trustAliases.isNotEmpty()) {
      settings[TRUST] = JsonArray(trustAliases.map { JsonPrimitive(it) })
    }
    clientCertAlias?.let { settings[CLIENT] = JsonPrimitive(it) }
  }

  /** What reading the members of some settings found. */
  sealed interface Parsed {
    class Valid(val aliases: TlsAliases) : Parsed

    /** [shape]: a member of the wrong shape; otherwise an alias not written like one. */
    class Invalid(val shape: Boolean) : Parsed
  }

  companion object {
    val NONE = TlsAliases(emptyList(), null)

    /** The names of the members in a type's settings. */
    const val TRUST = "trustAliases"
    const val CLIENT = "clientCertAlias"
    val MEMBERS = setOf(TRUST, CLIENT)

    /** Written like the name of a resource, as the other aliases are (WI-46). */
    private val ALIAS = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")
    private const val MOST_TRUSTED = 16

    /**
     * Reads [TRUST] (a list of distinct aliases, at most 16) and [CLIENT] (one alias) of
     * [settings].
     */
    fun parse(settings: JsonObject): Parsed {
      val trust = settings[TRUST]
      val client = settings[CLIENT]
      val trusted =
          when (trust) {
            null -> emptyList()
            !is JsonArray -> return Parsed.Invalid(shape = true)
            else ->
                trust.map {
                  (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
                      ?: return Parsed.Invalid(shape = true)
                }
          }
      val clientAlias =
          when (client) {
            null -> null
            else ->
                (client as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: return Parsed.Invalid(shape = true)
          }
      if ((trusted + listOfNotNull(clientAlias)).any { !ALIAS.matches(it) }) {
        return Parsed.Invalid(shape = false)
      }
      val normalized = trusted.map { it.lowercase(Locale.ROOT) }
      if (normalized.size > MOST_TRUSTED || normalized.toSet().size != normalized.size) {
        return Parsed.Invalid(shape = true)
      }
      return Parsed.Valid(TlsAliases(normalized, clientAlias?.lowercase(Locale.ROOT)))
    }
  }
}
