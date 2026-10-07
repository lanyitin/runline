package dev.lawlan.runline.accessors.openai

import dev.lawlan.runline.accessors.ResourceOperationFailure
import dev.lawlan.runline.core.ResourceFailure
import java.net.URI
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * What a call of a pipeline comes to once the resource's rules have been applied (WI-46): the
 * method and address of the catalog entry, the body with the resource's defaults under the
 * pipeline's values, and the limits on time. Everything a pipeline gave that the rules do not allow
 * is refused here, as a category, before anything is sent. The address is the resource's base
 * address and the entry's path; the method is the entry's; headers are not part of a plan at all.
 */
class OpenAiRequestPlan
private constructor(
    val endpoint: OpenAiEndpoint,
    val uri: URI,
    /** The JSON to send, or null for an entry without a body. */
    val body: ByteArray?,
    val limits: OpenAiLimits,
) {
  val method: String
    get() = endpoint.method

  companion object {
    private val TIMEOUT_NAMES = setOf("connect", "firstByte", "idle", "total", "quotaWait")

    /**
     * Applies [settings] to the [arguments] of one call; fails with a [ResourceOperationFailure].
     */
    fun of(settings: OpenAiSettings, arguments: Map<String, Any?>): OpenAiRequestPlan {
      val name = arguments["endpoint"] as? String ?: throw invalid()
      val endpoint =
          OpenAiEndpoints.find(name)
              ?: throw ResourceOperationFailure(ResourceFailure.UNKNOWN_ENDPOINT)
      if (endpoint.id !in settings.endpoints) {
        throw ResourceOperationFailure(ResourceFailure.ENDPOINT_NOT_ENABLED)
      }
      val path = endpoint.pathFor(stringMap(arguments["pathParameters"]))
      val query = endpoint.queryFor(stringMap(arguments["query"]))
      val limits = limitsOf(settings.timeouts, arguments["timeoutsMillis"])
      val text = arguments["body"]
      if (text != null && text !is String) throw invalid()
      val body = bodyOf(settings, endpoint, text as String?)
      return OpenAiRequestPlan(
          endpoint,
          URI.create(settings.baseUrl.toString() + path + query),
          body,
          limits,
      )
    }

    private fun invalid() = ResourceOperationFailure(ResourceFailure.INVALID_ARGUMENT)

    private fun stringMap(value: Any?): Map<String, String> {
      if (value == null) return emptyMap()
      val map = value as? Map<*, *> ?: throw invalid()
      return map.entries.associate { (key, entry) ->
        (key as? String ?: throw invalid()) to (entry as? String ?: throw invalid())
      }
    }

    /** The administrator's limits, each shortened to what the pipeline asked for, never beyond. */
    private fun limitsOf(admin: OpenAiLimits, asked: Any?): OpenAiLimits {
      if (asked == null) return admin
      val map = asked as? Map<*, *> ?: throw invalid()
      val millis = HashMap<String, Long>()
      for ((key, value) in map) {
        if (key !in TIMEOUT_NAMES) throw invalid()
        val number = value as? Long ?: throw invalid()
        if (number < 1) throw invalid()
        millis[key as String] = number
      }
      fun shorter(limit: Long, key: String) = minOf(limit, millis[key] ?: Long.MAX_VALUE)
      return OpenAiLimits(
          shorter(admin.connectMillis, "connect"),
          shorter(admin.firstByteMillis, "firstByte"),
          shorter(admin.idleMillis, "idle"),
          admin.totalMillis?.let { shorter(it, "total") } ?: millis["total"],
          shorter(admin.quotaWaitMillis, "quotaWait"),
      )
    }

    private fun bodyOf(
        settings: OpenAiSettings,
        endpoint: OpenAiEndpoint,
        text: String?,
    ): ByteArray? {
      if (endpoint.body == BodyKind.NONE) {
        if (text != null) throw invalid()
        return null
      }
      if (text != null && text.length > settings.maxRequestBytes) {
        throw ResourceOperationFailure(ResourceFailure.REQUEST_TOO_LARGE)
      }
      val given = if (text == null) JsonObject(emptyMap()) else objectOf(text)
      refuseStreaming(given)
      refuseLocked(settings, given)

      val merged = LinkedHashMap<String, JsonElement>()
      for ((key, value) in settings.defaults) {
        val applies = if (key == "model") endpoint.takesModel else endpoint.takesSampling
        if (applies) merged[key] = value
      }
      merged.putAll(given)

      if (endpoint.takesModel) checkModel(settings, merged["model"])
      checkCeilings(settings, given)

      val bytes = JsonObject(merged).toString().encodeToByteArray()
      if (bytes.size > settings.maxRequestBytes) {
        throw ResourceOperationFailure(ResourceFailure.REQUEST_TOO_LARGE)
      }
      return bytes
    }

    private fun objectOf(text: String): JsonObject =
        try {
          Json.parseToJsonElement(text) as? JsonObject ?: throw invalid()
        } catch (e: SerializationException) {
          throw invalid()
        } catch (e: IllegalArgumentException) {
          throw invalid()
        }

    /** `stream` belongs to the resource: a streamed answer is not what this call gives. */
    private fun refuseStreaming(given: JsonObject) {
      val stream = given["stream"]
      val off = stream is JsonPrimitive && !stream.isString && stream.content == "false"
      if ((stream != null && !off) || "stream_options" in given) {
        throw ResourceOperationFailure(ResourceFailure.STREAM_NOT_SUPPORTED)
      }
    }

    private fun refuseLocked(settings: OpenAiSettings, given: JsonObject) {
      if (given.keys.any { it in settings.lockedParameters }) {
        throw ResourceOperationFailure(ResourceFailure.PARAMETER_LOCKED)
      }
    }

    private fun checkModel(settings: OpenAiSettings, model: JsonElement?) {
      if (settings.allowedModels.isEmpty()) return
      val text =
          when {
            model == null -> throw ResourceOperationFailure(ResourceFailure.MODEL_NOT_ALLOWED)
            model is JsonPrimitive && model.isString -> model.content
            else -> throw invalid()
          }
      if (text !in settings.allowedModels) {
        throw ResourceOperationFailure(ResourceFailure.MODEL_NOT_ALLOWED)
      }
    }

    private fun checkCeilings(settings: OpenAiSettings, given: JsonObject) {
      for ((name, ceiling) in settings.maxValues) {
        val value = given[name] ?: continue
        val number =
            (value as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: throw invalid()
        if (number > ceiling) throw ResourceOperationFailure(ResourceFailure.VALUE_ABOVE_LIMIT)
      }
    }
  }
}
