package dev.lawlan.runline.devkit

import dev.lawlan.runline.accessors.suite.AccessorRig
import dev.lawlan.runline.accessors.suite.JdbcBehaviorSuite

/** The behavior every host of `jdbc-pool` accessors shows, here for the development entry. */
class DevJdbcBehaviorTest : JdbcBehaviorSuite() {
  override fun newRig(): AccessorRig = DevRig(record = false)
}

/** The same behavior while recording, which also records the use of the resource. */
class DevJdbcRecordingBehaviorTest : JdbcBehaviorSuite() {
  override fun newRig(): AccessorRig = DevRig(record = true)
}
