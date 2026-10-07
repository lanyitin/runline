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
) : Accessors {
  override fun file(name: String): FileAccessor {
    if (name !in metadata.resources) refuse(name, ResourceFailure.NOT_DECLARED)
    val declared = metadata.resourceTypes[name] ?: refuse(name, ResourceFailure.NO_TYPE_DECLARED)
    if (declared != ResourceTypes.FILE) refuse(name, ResourceFailure.TYPE_MISMATCH)
    val provided = link?.provided?.get(name)
    if (link == null || provided != ResourceTypes.FILE) {
      refuse(name, ResourceFailure.NOT_PROVIDED)
    }
    return HostFile(name, link, recorder)
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

  private fun call(operation: String, arguments: Map<String, Any?>): Any? {
    val request = java.util.HashMap<String, Any?>()
    request["resource"] = name
    request["operation"] = operation
    request["arguments"] = java.util.HashMap(arguments)
    val answer = link.call.apply(request)
    if (answer["ok"] == true) return answer["value"]
    throw ResourceAccessException(name, failureOf(answer["failure"]), answer["errorId"] as String?)
  }

  /** The category named by the host; one this copy of core does not know is a plain failure. */
  private fun failureOf(name: Any?): ResourceFailure {
    for (candidate in ResourceFailure.values()) if (candidate.name == name) return candidate
    return ResourceFailure.FAILED
  }
}
