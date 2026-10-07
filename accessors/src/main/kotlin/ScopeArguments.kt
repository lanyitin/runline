package dev.lawlan.runline.accessors

import dev.lawlan.runline.core.ResourceFailure
import java.nio.file.Path

/**
 * Where a run's two directories are, as the host was told by the Runner ([BoundResources]), and the
 * only place a directory enters a call: a call from the run names a scope and a relative path and
 * is made to say where that scope is by this class, whatever it carried about directories itself.
 */
internal class WorkspaceDirectories(
    private val shared: Path,
    private val run: Path,
    private val maxBytes: Long,
    private val writable: Map<String, Boolean>,
) {
  /**
   * [arguments] with every file part and the target of a download pointed at the real directory of
   * the scope it names, and held to what the scope may hold. Any `root` or `maxBytes` the run put
   * there is dropped. A scope that is not one of the two is [ResourceFailure.INVALID_ARGUMENT].
   */
  fun trusted(arguments: Map<String, Any?>): Map<String, Any?> {
    val result = HashMap(arguments)
    (arguments["files"] as? List<*>)?.let { files ->
      result["files"] = files.map { given ->
        val part = HashMap<String, Any?>(given as? Map<*, *> as? Map<String, Any?> ?: invalid())
        part.remove("root")
        part.remove("maxBytes")
        if (part["bytes"] == null) part["root"] = rootOf(part["scope"], writing = false)
        part
      }
    }
    arguments["target"]?.let { given ->
      val target = HashMap<String, Any?>(given as? Map<*, *> as? Map<String, Any?> ?: invalid())
      target["root"] = rootOf(target["scope"], writing = true)
      target["maxBytes"] = maxBytes
      result["target"] = target
    }
    return result
  }

  /**
   * The directory of [scope] if the pipeline declared it, and declared it writable when it is
   * [writing]: the host does not rely on the run's own check of that.
   */
  private fun rootOf(scope: Any?, writing: Boolean): String {
    val root =
        when (scope) {
          "PIPELINE_SHARED" -> shared
          "RUN_PRIVATE" -> run
          else -> invalid()
        }
    val mayWrite =
        writable[scope as String] ?: throw ResourceOperationFailure(ResourceFailure.PATH_REJECTED)
    if (writing && !mayWrite) throw ResourceOperationFailure(ResourceFailure.PATH_REJECTED)
    return root.toString()
  }

  private fun invalid(): Nothing = throw ResourceOperationFailure(ResourceFailure.INVALID_ARGUMENT)
}
