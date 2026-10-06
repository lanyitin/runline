package dev.lawlan.runline.engine.health

import io.opentelemetry.api.OpenTelemetry
import kotlin.test.*

class StartupCheckTest {
  private val lifecycle = EngineLifecycle()
  private val check = StartupCheck(lifecycle)

  @Test
  fun `is pending until the startup stages are done and ok after that`() {
    assertEquals("startup", check.name)
    assertEquals(CheckState.PENDING, check.check().state)

    lifecycle.completeStartup()

    assertEquals(CheckState.OK, check.check().state)
  }

  @Test
  fun `is not ready while pending, even when everything else is in order`() {
    val readiness =
        Readiness(
            listOf(check, ShutdownCheck(lifecycle)),
            lifecycle,
            HealthTelemetry(OpenTelemetry.noop()),
        )

    val before = readiness.evaluate()
    lifecycle.completeStartup()
    val after = readiness.evaluate()

    assertEquals(ReadinessStatus.NOT_READY, before.status)
    assertEquals(
        mapOf("startup" to CheckState.PENDING, "shutdown" to CheckState.OK),
        before.checks,
    )
    assertEquals(ReadinessStatus.READY, after.status)
  }
}
