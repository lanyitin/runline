package dev.lawlan.runline.engine.resource

import java.time.Instant

/** Why a check of a resource's entity failed; [wire] is its name in the API. */
enum class CheckFailure(val wire: String) {
  /** The root of the resource files is missing or cannot be used. */
  ROOT_UNAVAILABLE("root_unavailable"),

  /** The directory of the file does not exist and cannot be made. */
  PARENT_NOT_CREATABLE("parent_not_creatable"),

  /** The file is there but cannot be both read and written. */
  NOT_READABLE_WRITABLE("not_readable_writable"),

  /** The path leads out of the resource root. */
  PATH_OUTSIDE_ROOT("path_outside_root"),

  /** The check did not finish within the limit. */
  TIMEOUT("timeout"),

  /** The check itself failed for a reason not listed; the details are in the Engine's log. */
  ERROR("error");

  companion object {
    fun fromWire(wire: String): CheckFailure = entries.first { it.wire == wire }
  }
}

/** The last check of a resource: whether it passed, why not, and when. No detail beyond that. */
data class CheckResult(val ok: Boolean, val failure: CheckFailure?, val checkedAt: Instant) {
  init {
    require(ok == (failure == null)) { "a check passed or has a failure, not both" }
  }
}
