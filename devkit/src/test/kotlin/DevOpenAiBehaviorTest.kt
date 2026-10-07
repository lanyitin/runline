package dev.lawlan.runline.devkit

import dev.lawlan.runline.accessors.suite.AccessorRig
import dev.lawlan.runline.accessors.suite.OpenAiBehaviorSuite

/**
 * The behavior every host of `openai-compatible` accessors shows, here for the development entry.
 */
class DevOpenAiBehaviorTest : OpenAiBehaviorSuite() {
  override fun newRig(): AccessorRig = DevRig(record = false)
}

/** The same behavior while recording, which also records the use of the resource. */
class DevOpenAiRecordingBehaviorTest : OpenAiBehaviorSuite() {
  override fun newRig(): AccessorRig = DevRig(record = true)
}
