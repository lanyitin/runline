package dev.lawlan.runline.devkit

import dev.lawlan.runline.accessors.suite.AccessorRig
import dev.lawlan.runline.accessors.suite.SqliteBehaviorSuite

/** The behavior every host of `sqlite` accessors shows, here for the development entry. */
class DevSqliteBehaviorTest : SqliteBehaviorSuite() {
  override fun newRig(): AccessorRig = DevRig(record = false)
}

/** The same behavior while recording, which also records the use of the resource. */
class DevSqliteRecordingBehaviorTest : SqliteBehaviorSuite() {
  override fun newRig(): AccessorRig = DevRig(record = true)
}
