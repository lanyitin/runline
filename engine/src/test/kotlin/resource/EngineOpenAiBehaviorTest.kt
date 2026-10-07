package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.accessors.suite.AccessorRig
import dev.lawlan.runline.accessors.suite.OpenAiBehaviorSuite

/** The behavior every host of `openai-compatible` accessors shows, here for the Engine. */
class EngineOpenAiBehaviorTest : OpenAiBehaviorSuite() {
  override fun newRig(): AccessorRig = EngineRig()
}
