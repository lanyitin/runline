package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.accessors.suite.AccessorRig
import dev.lawlan.runline.accessors.suite.JdbcBehaviorSuite

/** The behavior every host of `jdbc-pool` accessors shows, here for the Engine. */
class EngineJdbcBehaviorTest : JdbcBehaviorSuite() {
  override fun newRig(): AccessorRig = EngineRig()
}
