package dev.lawlan.runline.engine.packaged

import dev.lawlan.runline.accessors.suite.AccessorRig
import dev.lawlan.runline.accessors.suite.JdbcBehaviorSuite
import dev.lawlan.runline.engine.support.PackagedEngineRig

/**
 * The acceptance tests of `jdbc-pool` against a real PostgreSQL, the same ones the development
 * entry passes (WI-48), here for the packaged Engine end to end (WI-51).
 */
class PackagedJdbcBehaviorTest : JdbcBehaviorSuite() {
  override fun newRig(): AccessorRig = PackagedEngineRig()
}
