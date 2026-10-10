package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.accessors.suite.AccessorRig
import dev.lawlan.runline.accessors.suite.SqliteBehaviorSuite

/** The behavior every host of `sqlite` accessors shows, here for the Engine. */
class EngineSqliteBehaviorTest : SqliteBehaviorSuite() {
  override fun newRig(): AccessorRig = EngineRig()
}
