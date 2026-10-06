package dev.lawlan.runline.engine

import kotlin.test.*

class MainTest {
  @Test
  fun `the server's shutdown timeout is the configured grace time`() {
    assertEquals(
        listOf("-P:ktor.deployment.shutdownTimeout=7000"),
        shutdownArguments(mapOf("RUNLINE_SHUTDOWN_GRACE_SECONDS" to "7")).toList(),
    )
  }

  @Test
  fun `without a configured grace time the default applies`() {
    assertEquals(
        listOf("-P:ktor.deployment.shutdownTimeout=30000"),
        shutdownArguments(emptyMap()).toList(),
    )
    assertEquals(
        listOf("-P:ktor.deployment.shutdownTimeout=30000"),
        shutdownArguments(mapOf("RUNLINE_SHUTDOWN_GRACE_SECONDS" to " ")).toList(),
    )
  }

  @Test
  fun `an invalid grace time is left for the configuration check to report`() {
    assertEquals(
        emptyList(),
        shutdownArguments(mapOf("RUNLINE_SHUTDOWN_GRACE_SECONDS" to "soon")).toList(),
    )
    assertEquals(
        emptyList(),
        shutdownArguments(mapOf("RUNLINE_SHUTDOWN_GRACE_SECONDS" to "-1")).toList(),
    )
  }
}
