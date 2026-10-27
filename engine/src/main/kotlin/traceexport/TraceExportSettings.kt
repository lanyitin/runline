package dev.lawlan.runline.engine.traceexport

import dev.lawlan.runline.accessors.tls.TlsAliases
import java.net.URI
import java.net.URISyntaxException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Why the settings of a `trace-export` resource are refused. */
enum class TraceExportSettingsProblem {
  /** A member that is missing, is not a setting, or has the wrong shape. */
  INVALID_SETTINGS,

  /** The endpoint is not an http(s) address of its own. */
  INVALID_ENDPOINT,

  /** A keystore alias of a certificate that is not written like one. */
  INVALID_ALIAS,
}

/**
 * The non-secret settings of a `trace-export` resource (ADR-46 decision 1): the OTLP endpoint (the
 * whole address of the traces signal, such as `https://host/api/public/otel/v1/traces`), the
 * service name the spans are exported under, and, for an https endpoint, the certificates the
 * connection trusts and presents (ADR-019 decision 12), by keystore alias. The credential is not
 * among them: the resource names its alias, and the keystore has the value.
 */
class TraceExportSettings
private constructor(val endpoint: URI, val serviceName: String, val tls: TlsAliases) {
  /** Every setting, in a fixed order, as it is stored. */
  fun normalized(): JsonObject {
    val result = LinkedHashMap<String, JsonElement>()
    result["endpoint"] = JsonPrimitive(endpoint.toString())
    result["serviceName"] = JsonPrimitive(serviceName)
    tls.writeTo(result)
    return JsonObject(result)
  }

  sealed interface Result {
    class Valid(val settings: TraceExportSettings) : Result

    class Invalid(val problem: TraceExportSettingsProblem) : Result
  }

  companion object {
    private val MEMBERS = setOf("endpoint", "serviceName") + TlsAliases.MEMBERS
    private const val LONGEST_SERVICE_NAME = 100

    fun parse(settings: JsonObject?): Result {
      if (settings == null || !MEMBERS.containsAll(settings.keys)) return invalid()
      val address = textOf(settings["endpoint"]) ?: return invalid()
      val endpoint =
          endpointOf(address) ?: return Result.Invalid(TraceExportSettingsProblem.INVALID_ENDPOINT)
      val serviceName = textOf(settings["serviceName"]) ?: return invalid()
      if (serviceName.length > LONGEST_SERVICE_NAME || serviceName.any { it.isISOControl() }) {
        return invalid()
      }
      val tls =
          when (val parsed = TlsAliases.parse(settings)) {
            is TlsAliases.Parsed.Invalid ->
                return Result.Invalid(
                    if (parsed.shape) TraceExportSettingsProblem.INVALID_SETTINGS
                    else TraceExportSettingsProblem.INVALID_ALIAS
                )
            is TlsAliases.Parsed.Valid -> parsed.aliases
          }
      // Certificates are for TLS: a plain http endpoint has no handshake to use them in.
      if (!tls.isEmpty && endpoint.scheme != "https") return invalid()
      return Result.Valid(TraceExportSettings(endpoint, serviceName, tls))
    }

    private fun invalid() = Result.Invalid(TraceExportSettingsProblem.INVALID_SETTINGS)

    private fun textOf(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }

    private fun endpointOf(text: String): URI? {
      if (text.any { it.code !in 0x21..0x7e }) return null
      val uri =
          try {
            URI(text)
          } catch (e: URISyntaxException) {
            return null
          }
      val scheme = uri.scheme?.lowercase()
      if (scheme != "http" && scheme != "https") return null
      if (uri.isOpaque || uri.host.isNullOrEmpty() || uri.rawUserInfo != null) return null
      if (uri.rawQuery != null || uri.rawFragment != null) return null
      return URI("$scheme://${uri.rawAuthority}${uri.rawPath.orEmpty()}")
    }
  }
}
