package dev.lawlan.runline.runner

import java.nio.file.Path
import java.time.Duration
import kotlin.test.*

class WorkspaceConfigTest {
  private val valid =
      mapOf(
          "RUNLINE_SHARED_ROOT" to "/data/shared",
          "RUNLINE_RUN_ROOT" to "/tmp/runs",
          "RUNLINE_WORKSPACE_MAX_BYTES" to "1048576",
          "RUNLINE_FAILED_RUN_RETENTION_SECONDS" to "3600",
      )

  @Test
  fun `reads every setting from the environment`() {
    val c = WorkspaceConfig.fromEnvironment(valid)

    assertEquals(Path.of("/data/shared"), c.sharedRoot)
    assertEquals(Path.of("/tmp/runs"), c.runRoot)
    assertEquals(1048576L, c.maxBytesPerScope)
    assertEquals(Duration.ofHours(1), c.failedRunRetention)
  }

  @Test
  fun `missing or invalid settings fail fast naming the variable`() {
    for (key in valid.keys) {
      val missing =
          assertFailsWith<IllegalStateException> { WorkspaceConfig.fromEnvironment(valid - key) }
      assertTrue(missing.message!!.contains(key), missing.message)
    }
    val invalid =
        mapOf(
            "RUNLINE_WORKSPACE_MAX_BYTES" to listOf("abc", "-1", "0"),
            "RUNLINE_FAILED_RUN_RETENTION_SECONDS" to listOf("abc", "-1"),
        )
    for ((key, values) in invalid) {
      for (bad in values) {
        val e =
            assertFailsWith<IllegalStateException>("$key=$bad") {
              WorkspaceConfig.fromEnvironment(valid + (key to bad))
            }
        assertTrue(e.message!!.contains(key), e.message)
      }
    }
  }
}
