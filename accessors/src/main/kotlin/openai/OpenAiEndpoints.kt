package dev.lawlan.runline.accessors.openai

import dev.lawlan.runline.accessors.ResourceOperationFailure
import dev.lawlan.runline.core.ResourceFailure

/** What the body of a request to an entry is. */
enum class BodyKind {
  NONE,
  JSON,
  MULTIPART,
}

/** What the answer of an entry is. A streaming entry also answers JSON when not asked to stream. */
enum class ResponseKind {
  JSON,
  BINARY,
}

/**
 * One entry of the endpoint catalog (ADR-019 decision 13): a request a pipeline may ask an
 * `openai-compatible` resource to make. Method, path, body and answer are fixed here; a pipeline
 * names the entry by [id] and can only fill the parameters the entry lists.
 */
class OpenAiEndpoint(
    val id: String,
    val method: String,
    /** The path below the resource's base address; `{name}` marks a path parameter. */
    val path: String,
    val body: BodyKind,
    val response: ResponseKind,
    /** Whether the service can answer with an event stream (WI-47). */
    val streams: Boolean = false,
    val queryParameters: Set<String> = emptySet(),
    /** Whether an administrator has to enable the entry: false only for the default four groups. */
    val defaultEnabled: Boolean = false,
    /** Whether the body names a model, so that the resource's model rules apply to it. */
    val takesModel: Boolean = false,
    /** Whether the body takes sampling and length parameters, which the resource may default. */
    val takesSampling: Boolean = false,
) {
  /** The names in braces in [path], in order. */
  val pathParameters: List<String> =
      Regex("\\{([a-z_]+)}").findAll(path).map { it.groupValues[1] }.toList()

  /**
   * Whether this version of the Engine can carry the entry out: a request that is JSON or empty
   * with an answer that is JSON. Multipart requests and binary answers come with WI-53.
   */
  val delivered: Boolean
    get() = body != BodyKind.MULTIPART && response != ResponseKind.BINARY

  /**
   * The path with the parameters in it, each one checked and nothing but a plain identifier let
   * through: no separator, no relative segment, no control character.
   */
  fun pathFor(parameters: Map<String, String>): String {
    if (parameters.keys != pathParameters.toSet()) throw invalid()
    var result = path
    for (name in pathParameters) {
      val value = parameters.getValue(name)
      if (!IDENTIFIER.matches(value) || value == "." || value == "..") throw invalid()
      result = result.replace("{$name}", value)
    }
    return result
  }

  /** The query string (with its `?`, or nothing) for the parameters this entry lists. */
  fun queryFor(query: Map<String, String>): String {
    if (query.isEmpty()) return ""
    if (!queryParameters.containsAll(query.keys)) throw invalid()
    return query.entries.joinToString("&", prefix = "?") { (name, value) ->
      if (value.length > MAX_QUERY_VALUE || value.any { it.code < 0x20 || it.code == 0x7f }) {
        throw invalid()
      }
      "$name=${encode(value)}"
    }
  }

  private fun invalid() = ResourceOperationFailure(ResourceFailure.INVALID_ARGUMENT)

  /** Percent-encoding of everything but the unreserved characters, byte by byte in UTF-8. */
  private fun encode(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
      val c = byte.toInt().toChar()
      if (byte >= 0 && (c.isLetterOrDigit() && c.code < 0x80 || c in "-._~")) append(c)
      else append('%').append("%02X".format(byte.toInt() and 0xff))
    }
  }

  private companion object {
    val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,256}")
    const val MAX_QUERY_VALUE = 1024
  }
}

/**
 * The catalog, versioned with the Engine. An entry added by a newer Engine is never enabled for a
 * resource that already exists: a resource stores the entries enabled for it, not "all of them".
 */
object OpenAiEndpoints {
  private fun json(
      id: String,
      method: String,
      path: String,
      streams: Boolean = false,
      query: Set<String> = emptySet(),
      default: Boolean = false,
      model: Boolean = false,
      sampling: Boolean = false,
  ) =
      OpenAiEndpoint(
          id,
          method,
          path,
          BodyKind.JSON,
          ResponseKind.JSON,
          streams,
          query,
          default,
          model,
          sampling,
      )

  private fun plain(
      id: String,
      method: String,
      path: String,
      query: Set<String> = emptySet(),
      default: Boolean = false,
  ) = OpenAiEndpoint(id, method, path, BodyKind.NONE, ResponseKind.JSON, false, query, default)

  private fun upload(id: String, path: String) =
      OpenAiEndpoint(id, "POST", path, BodyKind.MULTIPART, ResponseKind.JSON)

  val all: List<OpenAiEndpoint> =
      listOf(
          json(
              "chat.completions",
              "POST",
              "/chat/completions",
              true,
              default = true,
              model = true,
              sampling = true,
          ),
          json(
              "completions",
              "POST",
              "/completions",
              true,
              default = true,
              model = true,
              sampling = true,
          ),
          json("embeddings", "POST", "/embeddings", default = true, model = true),
          plain("models.list", "GET", "/models", default = true),
          plain("models.retrieve", "GET", "/models/{model}", default = true),
          json("responses.create", "POST", "/responses", true, model = true, sampling = true),
          plain("responses.retrieve", "GET", "/responses/{id}", setOf("include")),
          plain("responses.delete", "DELETE", "/responses/{id}"),
          plain("responses.cancel", "POST", "/responses/{id}/cancel"),
          plain(
              "responses.input_items",
              "GET",
              "/responses/{id}/input_items",
              setOf("after", "include", "limit", "order"),
          ),
          json("moderations", "POST", "/moderations", model = true),
          json("rerank", "POST", "/rerank", model = true),
          json("reranking", "POST", "/reranking", model = true),
          json("images.generations", "POST", "/images/generations", model = true),
          upload("images.edits", "/images/edits"),
          upload("images.variations", "/images/variations"),
          OpenAiEndpoint(
              "audio.speech",
              "POST",
              "/audio/speech",
              BodyKind.JSON,
              ResponseKind.BINARY,
              true,
              takesModel = true,
          ),
          upload("audio.transcriptions", "/audio/transcriptions"),
          upload("audio.translations", "/audio/translations"),
          upload("files.create", "/files"),
          plain("files.list", "GET", "/files", setOf("purpose", "limit", "after", "order")),
          plain("files.retrieve", "GET", "/files/{id}"),
          plain("files.delete", "DELETE", "/files/{id}"),
          OpenAiEndpoint(
              "files.content",
              "GET",
              "/files/{id}/content",
              BodyKind.NONE,
              ResponseKind.BINARY,
          ),
          json("batches.create", "POST", "/batches"),
          plain("batches.list", "GET", "/batches", setOf("after", "limit")),
          plain("batches.retrieve", "GET", "/batches/{id}"),
          plain("batches.cancel", "POST", "/batches/{id}/cancel"),
      )

  private val byId = all.associateBy { it.id }

  fun find(id: String): OpenAiEndpoint? = byId[id]

  /** The entries a new resource has enabled when its administrator does not choose. */
  val defaultEnabled: List<String> = all.filter { it.defaultEnabled }.map { it.id }
}
