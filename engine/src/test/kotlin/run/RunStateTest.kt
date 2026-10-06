package dev.lawlan.runline.engine.run

import kotlin.test.*

class RunStateTest {
  private val inOrder =
      listOf(
          RunState.QUEUED,
          RunState.WAITING_FOR_RESOURCES,
          RunState.INITIALIZING,
          RunState.RUNNING,
          RunState.TIMED_OUT_UNFINISHED,
      )

  @Test
  fun `a run moves forward through the lifecycle, skipping states it does not need`() {
    for ((i, from) in inOrder.withIndex()) {
      for (to in inOrder.drop(i + 1)) assertTrue(from.canAdvanceTo(to), "$from -> $to")
    }
  }

  @Test
  fun `a run never moves backward or stays in place`() {
    for ((i, from) in inOrder.withIndex()) {
      for (to in inOrder.take(i + 1)) assertFalse(from.canAdvanceTo(to), "$from -> $to")
    }
  }

  @Test
  fun `every non terminal state can reach every terminal state`() {
    for (from in inOrder) {
      for (to in RunState.entries.filter { it.terminal }) {
        assertTrue(from.canAdvanceTo(to), "$from -> $to")
      }
    }
  }

  @Test
  fun `a terminal state is final`() {
    for (from in RunState.entries.filter { it.terminal }) {
      for (to in RunState.entries) assertFalse(from.canAdvanceTo(to), "$from -> $to")
    }
  }
}
