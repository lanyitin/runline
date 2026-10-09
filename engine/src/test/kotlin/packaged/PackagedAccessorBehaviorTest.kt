package dev.lawlan.runline.engine.packaged

import dev.lawlan.runline.accessors.suite.AccessorBehaviorSuite
import dev.lawlan.runline.accessors.suite.AccessorRig
import dev.lawlan.runline.engine.support.PackagedEngineRig

/**
 * The acceptance tests of `file` and of the rules every accessor follows, the same ones the
 * development entry passes (WI-43), here for the packaged Engine end to end (WI-51): `engine.jar`
 * as its own process, its API and a real keystore.
 */
class PackagedAccessorBehaviorTest : AccessorBehaviorSuite() {
  override fun newRig(): AccessorRig = PackagedEngineRig()
}
