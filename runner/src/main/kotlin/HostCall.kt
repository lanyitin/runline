package dev.lawlan.runline.runner

import java.util.function.Function

/**
 * What the run calls to reach the host's side of its accessors, as a plain JDK [Function]. Whatever
 * the run passes in must be JDK built-in types (so no class of the run reaches the host), and
 * whatever the host answers is checked the same way; a host that fails gives the run a failure
 * answer, never an exception of the host's own type.
 */
internal class HostCall(private val host: ResourceHost) :
    Function<Map<String, Any?>, Map<String, Any?>> {
  override fun apply(request: Map<String, Any?>): Map<String, Any?> =
      try {
        BoundaryTypes.requireJdkOnly(request)
        host.call(request).also(BoundaryTypes::requireJdkOnly)
      } catch (e: Throwable) {
        java.util.HashMap<String, Any?>().apply {
          put("ok", false)
          put("failure", "FAILED")
        }
      }
}
