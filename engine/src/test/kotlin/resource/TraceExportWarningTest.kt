package dev.lawlan.runline.engine.resource

import ch.qos.logback.classic.Level
import dev.lawlan.runline.engine.support.CapturedLogs
import dev.lawlan.runline.engine.traceexport.DropReason
import dev.lawlan.runline.engine.traceexport.TraceExportTelemetry
import io.opentelemetry.api.OpenTelemetry
import java.time.Duration
import kotlin.test.*

/**
 * The warnings of dropped spans are limited in how often they are made, and the number of the ones
 * held back belongs to the window it was held back in (#64): it is said when the window ends, and a
 * warning long after does not carry a number that has piled up since an earlier run.
 */
class TraceExportWarningTest {
  private val logs = CapturedLogs()
  private val window = Duration.ofMillis(400)
  private val telemetry = TraceExportTelemetry(OpenTelemetry.noop(), window)

  @AfterTest
  fun close() {
    logs.close()
  }

  private fun warnings() = logs.at(Level.WARN).filter { it.contains("trace-export") }

  private fun await(what: String, condition: () -> Boolean) {
    val deadline = System.nanoTime() + 10_000_000_000
    while (!condition()) {
      check(System.nanoTime() < deadline) { "gave up waiting for $what" }
      Thread.sleep(10)
    }
  }

  @Test
  fun `the first warning is made at once and the ones held back are counted when the window ends`() {
    repeat(5) { telemetry.dropped("langfuse", 7, DropReason.QUEUE_FULL) }

    await("the first warning") { warnings().isNotEmpty() }
    assertTrue(warnings().first().contains("7 span(s)"), warnings().toString())
    await("the count of the window") { warnings().size == 2 }
    val held = warnings().last()
    assertTrue(held.contains("langfuse") && held.contains("queue_full"), held)
    assertTrue(held.contains("4"), held)
  }

  @Test
  fun `a window in which nothing was held back ends without a word`() {
    telemetry.dropped("langfuse", 1, DropReason.QUEUE_FULL)

    Thread.sleep(window.toMillis() * 2)

    assertEquals(1, warnings().size, warnings().toString())
  }

  @Test
  fun `a warning after the window starts a new one and does not carry the count of an earlier one`() {
    repeat(50) { telemetry.dropped("langfuse", 1, DropReason.QUEUE_FULL) }
    await("the count of the first window") { warnings().size == 2 }
    assertTrue(warnings().last().contains("49"), warnings().toString())

    telemetry.dropped("langfuse", 2, DropReason.QUEUE_FULL)

    await("the warning of the new window") { warnings().size == 3 }
    assertTrue(warnings().last().contains("2 span(s)"), warnings().toString())
    assertFalse(warnings().last().contains("49"), warnings().toString())
  }

  @Test
  fun `resources and reasons have windows of their own`() {
    telemetry.dropped("one", 1, DropReason.QUEUE_FULL)
    telemetry.dropped("two", 1, DropReason.QUEUE_FULL)
    telemetry.dropped("one", 1, DropReason.EXPORT_FAILED)

    await("a warning for each") { warnings().size == 3 }
  }
}
