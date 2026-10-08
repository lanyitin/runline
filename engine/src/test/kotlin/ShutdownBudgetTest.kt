package dev.lawlan.runline.engine

import java.time.Duration
import kotlin.test.*

/** The grace time of a shutdown is one budget that every step of the shutdown draws on (04). */
class ShutdownBudgetTest {
  private var now = 1_000_000_000L
  private val budget = ShutdownBudget(Duration.ofSeconds(30)) { now }

  private fun pass(duration: Duration) {
    now += duration.toNanos()
  }

  @Test
  fun `what is left shrinks from the moment the shutdown begins, whoever asks`() {
    pass(Duration.ofMinutes(5)) // serving: the budget is not drawn on before the shutdown
    budget.start()
    pass(Duration.ofSeconds(12)) // the requests in flight
    assertEquals(Duration.ofSeconds(18), budget.remaining())
    pass(Duration.ofSeconds(8)) // the runs
    assertEquals(Duration.ofSeconds(10), budget.remaining())
  }

  @Test
  fun `starting again does not give the time back`() {
    budget.start()
    pass(Duration.ofSeconds(20))
    budget.start()
    assertEquals(Duration.ofSeconds(10), budget.remaining())
  }

  @Test
  fun `once it is spent nothing is left, never less than nothing`() {
    budget.start()
    pass(Duration.ofSeconds(45))
    assertEquals(Duration.ZERO, budget.remaining())
  }

  @Test
  fun `a step that asks first begins it`() {
    // A scheduler closed on its own, as in a test or a stop that skipped the request step.
    assertEquals(Duration.ofSeconds(30), budget.remaining())
    pass(Duration.ofSeconds(1))
    assertEquals(Duration.ofSeconds(29), budget.remaining())
  }
}
