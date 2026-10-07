package dev.lawlan.runline.core

/**
 * The accessors of a run, inside the run's class loader. A resource is reachable only when the
 * pipeline declared it with a type, the type is the one asked for, and the host provided an
 * accessor for it; there is no way to obtain a resource while the run executes (ADR-007). The
 * operations themselves run on the host's side through [ResourceLink.call].
 */
internal class HostAccessors(
    private val metadata: PipelineMetadata,
    private val link: ResourceLink?,
    private val recorder: IoRecorder? = null,
    /** The scopes the pipeline may use and how, which is what a file of a request is held to. */
    private val scopes: Map<FileScope, FileMode> = metadata.files,
) : Accessors {
  override fun file(name: String): FileAccessor =
      HostFile(name, linkFor(name, ResourceTypes.FILE), recorder)

  override fun jdbcPool(name: String): JdbcAccessor =
      HostJdbc(name, linkFor(name, ResourceTypes.JDBC_POOL), recorder)

  override fun openAiCompatible(name: String): OpenAiAccessor =
      HostOpenAi(name, linkFor(name, ResourceTypes.OPENAI_COMPATIBLE), recorder, scopes)

  /** The host's link, once the pipeline has the right to an accessor of [type] for [name]. */
  private fun linkFor(name: String, type: String): ResourceLink {
    if (name !in metadata.resources) refuse(name, ResourceFailure.NOT_DECLARED)
    val declared = metadata.resourceTypes[name] ?: refuse(name, ResourceFailure.NO_TYPE_DECLARED)
    if (declared != type) refuse(name, ResourceFailure.TYPE_MISMATCH)
    val provided = link?.provided?.get(name)
    if (link == null || provided != type) refuse(name, ResourceFailure.NOT_PROVIDED)
    return link
  }

  private fun refuse(name: String, failure: ResourceFailure): Nothing =
      throw ResourceAccessException(name, failure)
}

/** One `file` accessor: every operation is a call to the host, whose answer is JDK types only. */
private class HostFile(
    private val name: String,
    private val link: ResourceLink,
    private val recorder: IoRecorder?,
) : FileAccessor {
  override fun readText(): String {
    record(IoAccess.READ)
    return call("file.read", emptyMap()) as String
  }

  override fun readBytes(): ByteArray {
    record(IoAccess.READ)
    return call("file.readBytes", emptyMap()) as ByteArray
  }

  override fun writeText(text: String) {
    record(IoAccess.WRITE)
    call("file.write", mapOf("text" to text))
  }

  override fun writeBytes(bytes: ByteArray) {
    record(IoAccess.WRITE)
    call("file.writeBytes", mapOf("bytes" to bytes))
  }

  override fun appendText(text: String) {
    record(IoAccess.WRITE)
    call("file.append", mapOf("text" to text))
  }

  override fun appendBytes(bytes: ByteArray) {
    record(IoAccess.WRITE)
    call("file.appendBytes", mapOf("bytes" to bytes))
  }

  /** Only the name, the type and the kind of action: never a path or content. */
  private fun record(access: IoAccess) {
    recorder?.record(IoCategory.RESOURCE, name, access, resourceType = ResourceTypes.FILE)
  }

  private fun call(operation: String, arguments: Map<String, Any?>): Any? =
      callHost(link, name, operation, arguments)
}

/** One `jdbc-pool` accessor: every statement is a call to the host, whose answer is JDK types. */
private class HostJdbc(
    private val name: String,
    private val link: ResourceLink,
    private val recorder: IoRecorder?,
) : JdbcAccessor {
  override fun query(sql: String): JdbcRows = query(sql, emptyList())

  override fun query(sql: String, parameters: List<Any?>): JdbcRows {
    val arguments = statement(sql, parameters, IoAccess.READ)
    val answer = callHost(link, name, "jdbc.query", arguments)
    return rowsOf(answer) ?: throw ResourceAccessException(name, ResourceFailure.FAILED)
  }

  override fun update(sql: String): Long = update(sql, emptyList())

  override fun update(sql: String, parameters: List<Any?>): Long {
    val arguments = statement(sql, parameters, IoAccess.WRITE)
    return callHost(link, name, "jdbc.update", arguments) as? Long
        ?: throw ResourceAccessException(name, ResourceFailure.FAILED)
  }

  override fun begin() = transaction("jdbc.begin")

  override fun commit() = transaction("jdbc.commit")

  override fun rollback() = transaction("jdbc.rollback")

  private fun transaction(operation: String) {
    record(IoAccess.WRITE)
    callHost(link, name, operation, emptyMap())
  }

  /**
   * The arguments of a statement for the host: its text and its parameters in the few shapes the
   * host takes. Only the name, the type and the kind of action are recorded: never SQL or a value.
   */
  private fun statement(
      sql: String,
      parameters: List<Any?>,
      access: IoAccess,
  ): java.util.HashMap<String, Any?> {
    val carried = java.util.ArrayList<Any?>(parameters.size)
    for (value in parameters) carried += carry(value)
    record(access)
    val arguments = java.util.HashMap<String, Any?>()
    arguments["sql"] = sql
    arguments["parameters"] = carried
    return arguments
  }

  /** A parameter as the host takes it; nothing that is not one of the listed kinds gets there. */
  private fun carry(value: Any?): Any? =
      when (value) {
        null,
        is String,
        is Boolean,
        is java.math.BigDecimal,
        is ByteArray -> value
        is Byte,
        is Short,
        is Int,
        is Long -> (value as Number).toLong()
        is Float,
        is Double -> (value as Number).toDouble()
        else -> throw ResourceAccessException(name, ResourceFailure.INVALID_ARGUMENT)
      }

  private fun rowsOf(answer: Any?): JdbcRows? {
    val map = answer as? Map<*, *> ?: return null
    val columns = (map["columns"] as? List<*>)?.map { it as? String ?: return null } ?: return null
    val rows =
        (map["rows"] as? List<*>)?.map { row ->
          (row as? List<*>)?.takeIf { it.size == columns.size } ?: return null
        } ?: return null
    return JdbcRows(columns, rows)
  }

  private fun record(access: IoAccess) {
    recorder?.record(IoCategory.RESOURCE, name, access, resourceType = ResourceTypes.JDBC_POOL)
  }
}

/** One `openai-compatible` accessor: a request becomes one call to the host. */
private class HostOpenAi(
    private val name: String,
    private val link: ResourceLink,
    private val recorder: IoRecorder?,
    private val scopes: Map<FileScope, FileMode>,
) : OpenAiAccessor {
  override fun call(request: OpenAiRequest): OpenAiResponse {
    @Suppress("UNCHECKED_CAST")
    val answer = callHost(link, name, "openai.call", arguments(request)) as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") val headers = answer["headers"] as Map<String, List<String>>
    return OpenAiResponse(answer["status"] as Int, headers, answer["body"] as String)
  }

  private fun arguments(request: OpenAiRequest): java.util.HashMap<String, Any?> {
    // Only the name, the type and the kind of action: never an endpoint, a body or an address.
    recorder?.record(
        IoCategory.RESOURCE,
        name,
        IoAccess.WRITE,
        resourceType = ResourceTypes.OPENAI_COMPATIBLE,
    )
    val arguments = java.util.HashMap<String, Any?>()
    arguments["endpoint"] = request.endpoint
    arguments["body"] = request.body
    arguments["pathParameters"] = java.util.HashMap(request.pathParameters)
    arguments["query"] = java.util.HashMap(request.query)
    arguments["timeoutsMillis"] = millisOf(request.timeouts)
    if (request.fields.isNotEmpty()) arguments["fields"] = java.util.HashMap(request.fields)
    if (request.files.isNotEmpty()) {
      arguments["files"] = java.util.ArrayList(request.files.map(::partOf))
    }
    sizesOf(request.sizes)?.let { arguments["sizesBytes"] = it }
    return arguments
  }

  override fun stream(request: OpenAiRequest): OpenAiStream {
    @Suppress("UNCHECKED_CAST")
    val opened = callHost(link, name, "openai.stream.open", arguments(request)) as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") val headers = opened["headers"] as Map<String, List<String>>
    return HostOpenAiStream(name, link, opened["stream"] as Long, opened["status"] as Int, headers)
  }

  /**
   * A file part for the host: the scope by name and the path relative to it, which is all a
   * pipeline names. Where the scope's directory is, and what it may hold, the host knows by itself;
   * nothing of that is in the call.
   */
  private fun partOf(upload: OpenAiUpload): Map<String, Any?> {
    val part = java.util.HashMap<String, Any?>()
    part["field"] = upload.field
    part["filename"] = upload.filename
    if (upload.bytes != null) {
      part["bytes"] = upload.bytes
    } else {
      val file = upload.file!!
      require(file.scope, writable = false)
      record(file, IoAccess.READ)
      part["scope"] = file.scope.name
      part["path"] = file.path
    }
    return part
  }

  private fun require(scope: FileScope, writable: Boolean) {
    val mode = scopes[scope]
    if (mode == null || (writable && !mode.writable)) {
      throw ResourceAccessException(name, ResourceFailure.PATH_REJECTED)
    }
  }

  /** Only the scope and the relative path of a file are recorded: never a host path. */
  private fun record(file: OpenAiFile, access: IoAccess) {
    recorder?.record(IoCategory.FILE, file.path, access, scope = file.scope)
  }

  private fun sizesOf(sizes: OpenAiSizes): Map<String, Long>? {
    val bytes = java.util.HashMap<String, Long>()
    sizes.request?.let { bytes["request"] = it }
    sizes.response?.let { bytes["response"] = it }
    sizes.download?.let { bytes["download"] = it }
    return bytes.ifEmpty { null }
  }

  override fun download(request: OpenAiRequest): OpenAiBinaryResponse {
    @Suppress("UNCHECKED_CAST")
    val answer = callHost(link, name, "openai.download", arguments(request)) as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") val headers = answer["headers"] as Map<String, List<String>>
    return OpenAiBinaryResponse(answer["status"] as Int, headers, answer["bytes"] as ByteArray)
  }

  override fun downloadTo(request: OpenAiRequest, target: OpenAiFile): OpenAiStoredResponse {
    require(target.scope, writable = true)
    val arguments = arguments(request)
    record(target, IoAccess.WRITE)
    val place = java.util.HashMap<String, Any?>()
    place["scope"] = target.scope.name
    place["path"] = target.path
    arguments["target"] = place
    @Suppress("UNCHECKED_CAST")
    val answer = callHost(link, name, "openai.download", arguments) as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") val headers = answer["headers"] as Map<String, List<String>>
    return OpenAiStoredResponse(
        answer["status"] as Int,
        headers,
        target.scope,
        answer["path"] as String,
        answer["size"] as Long,
    )
  }

  override fun streamBytes(request: OpenAiRequest): OpenAiByteStream {
    val arguments = arguments(request)
    arguments["binary"] = true
    @Suppress("UNCHECKED_CAST")
    val opened = callHost(link, name, "openai.stream.open", arguments) as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") val headers = opened["headers"] as Map<String, List<String>>
    return HostOpenAiByteStream(
        name,
        link,
        opened["stream"] as Long,
        opened["status"] as Int,
        headers,
    )
  }

  /** The limits the pipeline asked for, by name, in milliseconds. */
  private fun millisOf(timeouts: OpenAiTimeouts): Map<String, Long> {
    val millis = java.util.HashMap<String, Long>()
    timeouts.connect?.let { millis["connect"] = it.toMillis() }
    timeouts.firstByte?.let { millis["firstByte"] = it.toMillis() }
    timeouts.idle?.let { millis["idle"] = it.toMillis() }
    timeouts.total?.let { millis["total"] = it.toMillis() }
    timeouts.quotaWait?.let { millis["quotaWait"] = it.toMillis() }
    return millis
  }
}

/** A streamed answer: the host holds the stream, this is the pipeline's handle on it. */
private class HostOpenAiStream(
    private val name: String,
    private val link: ResourceLink,
    private val id: Long,
    override val status: Int,
    override val headers: Map<String, List<String>>,
) : OpenAiStream {
  @Volatile private var ended = false

  override fun next(): String? {
    if (ended) return null
    val data = callHost(link, name, "openai.stream.next", mapOf("stream" to id)) as String?
    if (data == null) ended = true
    return data
  }

  override fun close() {
    if (ended) return
    ended = true
    // A host that is done with the run says so by failing; there is nothing left to close then.
    try {
      callHost(link, name, "openai.stream.close", mapOf("stream" to id))
    } catch (e: ResourceAccessException) {
      // the stream is gone with the run
    }
  }
}

/** Audio pulled in chunks: the host holds the stream, this is the pipeline's handle on it. */
private class HostOpenAiByteStream(
    private val name: String,
    private val link: ResourceLink,
    private val id: Long,
    override val status: Int,
    override val headers: Map<String, List<String>>,
) : OpenAiByteStream {
  @Volatile private var ended = false

  override fun next(): ByteArray? {
    if (ended) return null
    val data = callHost(link, name, "openai.stream.next", mapOf("stream" to id)) as ByteArray?
    if (data == null) ended = true
    return data
  }

  override fun close() {
    if (ended) return
    ended = true
    try {
      callHost(link, name, "openai.stream.close", mapOf("stream" to id))
    } catch (e: ResourceAccessException) {
      // the stream is gone with the run
    }
  }
}

/**
 * One call to the host; its answer is JDK types only, a failure is an exception of the category.
 */
private fun callHost(
    link: ResourceLink,
    name: String,
    operation: String,
    arguments: Map<String, Any?>,
): Any? {
  val request = java.util.HashMap<String, Any?>()
  request["resource"] = name
  request["operation"] = operation
  request["arguments"] = java.util.HashMap(arguments)
  val answer = link.call.apply(request)
  if (answer["ok"] == true) return answer["value"]
  throw ResourceAccessException(
      name,
      failureOf(answer["failure"]),
      answer["errorId"] as String?,
      answer["status"] as Int?,
      answer["sqlState"] as? String,
  )
}

/** The category named by the host; one this copy of core does not know is a plain failure. */
private fun failureOf(name: Any?): ResourceFailure {
  for (candidate in ResourceFailure.values()) if (candidate.name == name) return candidate
  return ResourceFailure.FAILED
}
