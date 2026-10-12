package dev.lawlan.runline.engine.config

import io.ktor.server.config.*
import kotlin.test.*

/**
 * The shipped `application.yaml` hands the limit of view reads to the Engine only when it is set.
 */
class ViewLimitEnvironmentTest {
  @Test
  fun `the limit of view reads is absent in the shipped configuration while its variable is unset`() {
    assertNull(System.getenv("RUNLINE_VIEW_MAX_CONCURRENT_READS"), "the test needs it unset")

    val config = ConfigLoader.load("application.yaml")

    assertNull(config.propertyOrNull("views.maxReadsPerResource")?.getString())
  }
}
