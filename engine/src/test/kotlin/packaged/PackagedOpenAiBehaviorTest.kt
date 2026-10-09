package dev.lawlan.runline.engine.packaged

import dev.lawlan.runline.accessors.suite.AccessorRig
import dev.lawlan.runline.accessors.suite.OpenAiBehaviorSuite
import dev.lawlan.runline.engine.support.PackagedEngineRig

/**
 * The acceptance tests of `openai-compatible` against the Fake, the same ones the development entry
 * passes (WI-46), here for the packaged Engine end to end (WI-51).
 */
class PackagedOpenAiBehaviorTest : OpenAiBehaviorSuite() {
  override fun newRig(): AccessorRig = PackagedEngineRig()
}
