package dev.lawlan.runline.accessors.openai

import java.net.URI
import java.net.URISyntaxException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Why the settings of an `openai-compatible` resource are refused; [wire] names it in the API. */
enum class OpenAiSettingsProblem(val wire: String) {
  /** A member that is missing, is not a setting, or has the wrong shape. */
  INVALID_SETTINGS("invalid_settings"),
  INVALID_BASE_URL("invalid_base_url"),
  INVALID_HEADER("invalid_header"),

  /** An endpoint that is not in the catalog, or not delivered by this Engine. */
  INVALID_ENDPOINT("invalid_endpoint"),
  INVALID_REQUEST_DEFAULTS("invalid_request_defaults"),
  INVALID_TIMEOUT("invalid_timeout"),

  /** Requests per run, or a size limit. */
  INVALID_LIMIT("invalid_limit"),
}

sealed interface SettingsResult {
  /** [normalized] has every effective value written out; it is what is stored. */
  class Valid(val settings: OpenAiSettings, val normalized: JsonObject) : SettingsResult

  class Invalid(val problem: OpenAiSettingsProblem) : SettingsResult
}

/** The five limits of ADR-019 decision 14 that an administrator sets, in milliseconds. */
data class OpenAiLimits(
    val connectMillis: Long,
    val firstByteMillis: Long,
    val idleMillis: Long,
    /** Null: the call as a whole is not limited. */
    val totalMillis: Long?,
    val quotaWaitMillis: Long,
)

/** The three limits on size that apply to one call, in bytes. */
data class OpenAiSizes(val request: Long, val response: Long, val download: Long)

/**
 * The settings an administrator fixes for an `openai-compatible` resource (ADR-019): where the
 * service is, what goes out with every request, which catalog entries are open, how long each stage
 * may take, and what a pipeline may change about a request. The API key is not among them; a
 * resource names it by alias.
 */
class OpenAiSettings
internal constructor(
    /** Without a trailing slash; the path is part of the address (`/v1`). */
    val baseUrl: URI,
    val organization: String?,
    val project: String?,
    val headers: Map<String, String>,
    /** The enabled entries, in catalog order. */
    val endpoints: Set<String>,
    val timeouts: OpenAiLimits,
    /** How many requests one run may have in flight at once. */
    val requestsPerRun: Int,
    val maxRequestBytes: Long,
    /** The most of an answer that is kept in memory. */
    val maxResponseBytes: Long,
    /** The most of a binary answer that is written to a file. */
    val maxDownloadBytes: Long,
    val defaults: JsonObject,
    /** Empty: any model. */
    val allowedModels: Set<String>,
    val lockedParameters: Set<String>,
    /** Ceilings for numeric parameters, by name. */
    val maxValues: Map<String, Double>,
) {
  companion object {
    private val MEMBERS =
        setOf(
            "baseUrl",
            "organization",
            "project",
            "headers",
            "endpoints",
            "timeouts",
            "requestsPerRun",
            "maxRequestBytes",
            "maxResponseBytes",
            "maxDownloadBytes",
            "defaults",
            "allowedModels",
            "lockedParameters",
            "maxValues",
        )
    private val TIMEOUT_MEMBERS =
        setOf("connectMs", "firstByteMs", "idleMs", "totalMs", "quotaWaitMs")

    const val DEFAULT_CONNECT_MS = 10_000L
    const val DEFAULT_FIRST_BYTE_MS = 15L * 60 * 1000
    const val DEFAULT_IDLE_MS = 5L * 60 * 1000
    const val DEFAULT_QUOTA_WAIT_MS = 60_000L
    const val DEFAULT_MAX_REQUEST_BYTES = 32L * 1024 * 1024
    const val DEFAULT_MAX_RESPONSE_BYTES = 8L * 1024 * 1024
    const val DEFAULT_MAX_DOWNLOAD_BYTES = 256L * 1024 * 1024
    private const val LONGEST_TIMEOUT_MS = 24L * 3600 * 1000
    private const val MOST_REQUESTS_PER_RUN = 256
    private const val MOST_REQUEST_BYTES = 1L shl 30
    private const val MOST_RESPONSE_BYTES = 256L * 1024 * 1024
    private const val MOST_DOWNLOAD_BYTES = 16L * 1024 * 1024 * 1024

    /** Parameters whose value is a number. */
    private val NUMERIC =
        setOf(
            "temperature",
            "top_p",
            "top_k",
            "min_p",
            "max_tokens",
            "max_completion_tokens",
            "max_output_tokens",
            "seed",
            "presence_penalty",
            "frequency_penalty",
            "repeat_penalty",
            "n",
        )

    /**
     * The only parameters a resource may default, lock or put a ceiling on: the model and the
     * sampling and length parameters. Messages, tools and the rest are the pipeline's alone, and
     * `stream` is the resource's own business.
     */
    val DEFAULTABLE: Set<String> =
        NUMERIC + setOf("model", "stop", "response_format", "reasoning_effort")

    /** Checks [settings] and returns them with every effective value written out. */
    fun parse(settings: JsonObject): SettingsResult {
      if (!MEMBERS.containsAll(settings.keys))
          return invalid(OpenAiSettingsProblem.INVALID_SETTINGS)
      val baseUrl =
          when (val value = settings["baseUrl"]) {
            null -> return invalid(OpenAiSettingsProblem.INVALID_SETTINGS)
            else -> baseUrlOf(value) ?: return invalid(OpenAiSettingsProblem.INVALID_BASE_URL)
          }
      val organization = optionalValue(settings, "organization")
      val project = optionalValue(settings, "project")
      if (organization is Bad || project is Bad) {
        return invalid(
            if (organization is Bad && organization.shape || project is Bad && project.shape)
                OpenAiSettingsProblem.INVALID_SETTINGS
            else OpenAiSettingsProblem.INVALID_HEADER
        )
      }
      val headers =
          headersOf(settings["headers"]) ?: return invalid(OpenAiSettingsProblem.INVALID_HEADER)
      val endpoints =
          endpointsOf(settings["endpoints"])
              ?: return invalid(OpenAiSettingsProblem.INVALID_ENDPOINT)
      val timeouts =
          timeoutsOf(settings["timeouts"]) ?: return invalid(OpenAiSettingsProblem.INVALID_TIMEOUT)
      val requestsPerRun =
          boundedLong(settings["requestsPerRun"], 1, MOST_REQUESTS_PER_RUN.toLong(), 1)
              ?: return invalid(OpenAiSettingsProblem.INVALID_LIMIT)
      val maxRequestBytes =
          boundedLong(settings["maxRequestBytes"], 1, MOST_REQUEST_BYTES, DEFAULT_MAX_REQUEST_BYTES)
              ?: return invalid(OpenAiSettingsProblem.INVALID_LIMIT)
      val maxResponseBytes =
          boundedLong(
              settings["maxResponseBytes"],
              1,
              MOST_RESPONSE_BYTES,
              DEFAULT_MAX_RESPONSE_BYTES,
          ) ?: return invalid(OpenAiSettingsProblem.INVALID_LIMIT)
      val maxDownloadBytes =
          boundedLong(
              settings["maxDownloadBytes"],
              1,
              MOST_DOWNLOAD_BYTES,
              DEFAULT_MAX_DOWNLOAD_BYTES,
          ) ?: return invalid(OpenAiSettingsProblem.INVALID_LIMIT)
      val defaults =
          defaultsOf(settings["defaults"])
              ?: return invalid(OpenAiSettingsProblem.INVALID_REQUEST_DEFAULTS)
      val allowedModels =
          stringSet(settings["allowedModels"])
              ?: return invalid(OpenAiSettingsProblem.INVALID_REQUEST_DEFAULTS)
      val locked =
          stringSet(settings["lockedParameters"])
              ?: return invalid(OpenAiSettingsProblem.INVALID_REQUEST_DEFAULTS)
      val maxValues =
          maxValuesOf(settings["maxValues"])
              ?: return invalid(OpenAiSettingsProblem.INVALID_REQUEST_DEFAULTS)
      if (!DEFAULTABLE.containsAll(locked))
          return invalid(OpenAiSettingsProblem.INVALID_REQUEST_DEFAULTS)
      if (!ownRulesHold(defaults, allowedModels, maxValues)) {
        return invalid(OpenAiSettingsProblem.INVALID_REQUEST_DEFAULTS)
      }

      val parsed =
          OpenAiSettings(
              baseUrl,
              (organization as? Present)?.text,
              (project as? Present)?.text,
              headers,
              endpoints,
              timeouts,
              requestsPerRun.toInt(),
              maxRequestBytes,
              maxResponseBytes,
              maxDownloadBytes,
              defaults,
              allowedModels,
              locked,
              maxValues,
          )
      return SettingsResult.Valid(parsed, parsed.normalized())
    }

    private fun invalid(problem: OpenAiSettingsProblem) = SettingsResult.Invalid(problem)

    private fun baseUrlOf(value: JsonElement): URI? {
      val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
      if (text.isEmpty() || text.any { it.code !in 0x21..0x7e }) return null
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
      val path = uri.rawPath.orEmpty().trimEnd('/')
      if (path.split('/').any { it == "." || it == ".." } || path.contains("//")) return null
      val authority = uri.rawAuthority
      return URI("$scheme://$authority$path")
    }

    private sealed interface Optional

    private data object Absent : Optional

    private class Present(val text: String) : Optional

    /** [shape]: the value is not a non-empty string; otherwise it is not a header value. */
    private class Bad(val shape: Boolean) : Optional

    private fun optionalValue(settings: JsonObject, key: String): Optional {
      val element = settings[key] ?: return Absent
      val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
      if (text.isNullOrEmpty()) return Bad(shape = true)
      return if (HeaderRules.isValidValue(text)) Present(text) else Bad(shape = false)
    }

    private fun headersOf(element: JsonElement?): Map<String, String>? {
      if (element == null) return emptyMap()
      val headers = element as? JsonObject ?: return null
      if (headers.size > 32) return null
      val result = LinkedHashMap<String, String>()
      for ((name, value) in headers) {
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (!HeaderRules.isAllowedExtraName(name) || !HeaderRules.isValidValue(text)) return null
        if (result.keys.any { it.equals(name, ignoreCase = true) }) return null
        result[name] = text
      }
      return result
    }

    private fun endpointsOf(element: JsonElement?): Set<String>? {
      if (element == null) return LinkedHashSet(OpenAiEndpoints.defaultEnabled)
      val names = stringList(element) ?: return null
      if (names.isEmpty()) return null
      for (name in names) {
        if (OpenAiEndpoints.find(name)?.delivered != true) return null
      }
      // In catalog order, so that what is stored does not depend on how it was written.
      return OpenAiEndpoints.all.map { it.id }.filterTo(LinkedHashSet()) { it in names }
    }

    private fun timeoutsOf(element: JsonElement?): OpenAiLimits? {
      if (element == null) {
        return OpenAiLimits(
            DEFAULT_CONNECT_MS,
            DEFAULT_FIRST_BYTE_MS,
            DEFAULT_IDLE_MS,
            null,
            DEFAULT_QUOTA_WAIT_MS,
        )
      }
      val timeouts = element as? JsonObject ?: return null
      if (!TIMEOUT_MEMBERS.containsAll(timeouts.keys)) return null
      val connect =
          boundedLong(timeouts["connectMs"], 1, LONGEST_TIMEOUT_MS, DEFAULT_CONNECT_MS)
              ?: return null
      val firstByte =
          boundedLong(timeouts["firstByteMs"], 1, LONGEST_TIMEOUT_MS, DEFAULT_FIRST_BYTE_MS)
              ?: return null
      val idle =
          boundedLong(timeouts["idleMs"], 1, LONGEST_TIMEOUT_MS, DEFAULT_IDLE_MS) ?: return null
      val quotaWait =
          boundedLong(timeouts["quotaWaitMs"], 1, LONGEST_TIMEOUT_MS, DEFAULT_QUOTA_WAIT_MS)
              ?: return null
      val total =
          when (val value = timeouts["totalMs"]) {
            null,
            JsonNull -> null
            else -> boundedLong(value, 1, LONGEST_TIMEOUT_MS, 0) ?: return null
          }
      return OpenAiLimits(connect, firstByte, idle, total, quotaWait)
    }

    /** [element] as a whole number in [min]..[max], [default] when there is none; null if wrong. */
    private fun boundedLong(element: JsonElement?, min: Long, max: Long, default: Long): Long? {
      if (element == null) return default
      val primitive = element as? JsonPrimitive ?: return null
      if (primitive.isString) return null
      return primitive.longOrNull?.takeIf { it in min..max }
    }

    private fun stringList(element: JsonElement?): List<String>? {
      val array = element as? JsonArray ?: return null
      return array.map { item ->
        (item as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
            ?: return null
      }
    }

    private fun stringSet(element: JsonElement?): Set<String>? =
        if (element == null) emptySet() else stringList(element)?.toCollection(LinkedHashSet())

    private fun defaultsOf(element: JsonElement?): JsonObject? {
      if (element == null) return JsonObject(emptyMap())
      val defaults = element as? JsonObject ?: return null
      if (!DEFAULTABLE.containsAll(defaults.keys)) return null
      for ((name, value) in defaults) if (!fits(name, value)) return null
      return defaults
    }

    /** Whether [value] is the kind of value the parameter [name] takes. */
    private fun fits(name: String, value: JsonElement): Boolean =
        when (name) {
          in NUMERIC -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
          "stop" -> value.isString() || stringList(value) != null
          "response_format" -> value is JsonObject
          else ->
              value.isString() &&
                  (value as JsonPrimitive).content.isNotEmpty() // model, reasoning_effort
        }

    private fun JsonElement.isString() = this is JsonPrimitive && isString

    private fun maxValuesOf(element: JsonElement?): Map<String, Double>? {
      if (element == null) return emptyMap()
      val values = element as? JsonObject ?: return null
      val result = LinkedHashMap<String, Double>()
      for ((name, value) in values) {
        if (name !in NUMERIC) return null
        val number = (value as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return null
        if (!(number > 0.0) || number.isInfinite()) return null
        result[name] = number
      }
      return result
    }

    /** The defaults must obey the administrator's own allowed models and ceilings. */
    private fun ownRulesHold(
        defaults: JsonObject,
        allowedModels: Set<String>,
        maxValues: Map<String, Double>,
    ): Boolean {
      val model = (defaults["model"] as? JsonPrimitive)?.content
      if (model != null && allowedModels.isNotEmpty() && model !in allowedModels) return false
      for ((name, ceiling) in maxValues) {
        val value = (defaults[name] as? JsonPrimitive)?.doubleOrNull ?: continue
        if (value > ceiling) return false
      }
      return true
    }
  }

  /** The limits on size of a call, in bytes. */
  val sizes: OpenAiSizes
    get() = OpenAiSizes(maxRequestBytes, maxResponseBytes, maxDownloadBytes)

  /** The same settings with other limits on time, for what looks at the service on its own. */
  fun withTimeouts(limits: OpenAiLimits) =
      OpenAiSettings(
          baseUrl,
          organization,
          project,
          headers,
          endpoints,
          limits,
          requestsPerRun,
          maxRequestBytes,
          maxResponseBytes,
          maxDownloadBytes,
          defaults,
          allowedModels,
          lockedParameters,
          maxValues,
      )

  /** Every effective value, in a fixed order, so that equal settings are written equally. */
  private fun normalized(): JsonObject {
    val result = LinkedHashMap<String, JsonElement>()
    result["baseUrl"] = JsonPrimitive(baseUrl.toString())
    organization?.let { result["organization"] = JsonPrimitive(it) }
    project?.let { result["project"] = JsonPrimitive(it) }
    if (headers.isNotEmpty()) {
      result["headers"] = JsonObject(headers.mapValues { JsonPrimitive(it.value) })
    }
    result["endpoints"] = JsonArray(endpoints.map { JsonPrimitive(it) })
    val limits = LinkedHashMap<String, JsonElement>()
    limits["connectMs"] = JsonPrimitive(timeouts.connectMillis)
    limits["firstByteMs"] = JsonPrimitive(timeouts.firstByteMillis)
    limits["idleMs"] = JsonPrimitive(timeouts.idleMillis)
    timeouts.totalMillis?.let { limits["totalMs"] = JsonPrimitive(it) }
    limits["quotaWaitMs"] = JsonPrimitive(timeouts.quotaWaitMillis)
    result["timeouts"] = JsonObject(limits)
    result["requestsPerRun"] = JsonPrimitive(requestsPerRun)
    result["maxRequestBytes"] = JsonPrimitive(maxRequestBytes)
    result["maxResponseBytes"] = JsonPrimitive(maxResponseBytes)
    result["maxDownloadBytes"] = JsonPrimitive(maxDownloadBytes)
    if (defaults.isNotEmpty()) result["defaults"] = defaults
    if (allowedModels.isNotEmpty())
        result["allowedModels"] = JsonArray(allowedModels.map { JsonPrimitive(it) })
    if (lockedParameters.isNotEmpty()) {
      result["lockedParameters"] = JsonArray(lockedParameters.map { JsonPrimitive(it) })
    }
    if (maxValues.isNotEmpty())
        result["maxValues"] = JsonObject(maxValues.mapValues { JsonPrimitive(it.value) })
    return JsonObject(result)
  }
}
