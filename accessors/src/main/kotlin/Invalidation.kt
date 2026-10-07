package dev.lawlan.runline.accessors

import dev.lawlan.runline.core.ResourceFailure

/** Why accessors stop working while the run that holds them may still be executing. */
enum class Invalidation(val failure: ResourceFailure) {
  RUN_ENDED(ResourceFailure.ENDED),
  FORCE_RELEASED(ResourceFailure.FORCE_RELEASED),
}
