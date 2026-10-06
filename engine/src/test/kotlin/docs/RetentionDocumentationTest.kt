package dev.lawlan.runline.engine.docs

import dev.lawlan.runline.engine.config.RetentionSettings
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * The README says what the code does about retention: every setting, and the defaults and floors.
 */
class RetentionDocumentationTest {
  private val readme = Files.readString(Path.of(System.getProperty("runline.readme")))
  private val yaml =
      checkNotNull(javaClass.getResourceAsStream("/application.yaml")).use {
        it.readBytes().decodeToString()
      }

  /** The README row of a setting, from the variable's name to the end of its table row. */
  private fun row(variable: String): String =
      readme.lines().first { it.startsWith("| `$variable`") }

  private val variables =
      Regex("""\s+\w+: "\$(RUNLINE_[A-Z_]*(?:RETENTION|DEDUP_WINDOW)[A-Z_]*):"""")
          .findAll(yaml.substringAfter("\nretention:"))
          .map { it.groupValues[1] }
          .toList()

  @Test
  fun `every retention variable of the configuration is in the README`() {
    assertEquals(6, variables.size, "the retention settings of application.yaml: $variables")
    for (variable in variables) assertTrue(row(variable).isNotBlank(), variable)
  }

  @Test
  fun `the README states the defaults and floors the code has`() {
    assertContains(
        row("RUNLINE_RUN_RETENTION_SECONDS"),
        "${RetentionSettings.DEFAULT_RUN_SECONDS}",
    )
    assertContains(
        row("RUNLINE_RUN_RETENTION_SECONDS"),
        "at least ${RetentionSettings.MIN_RUN_SECONDS}",
    )
    assertContains(
        row("RUNLINE_WEBHOOK_DEDUP_WINDOW_SECONDS"),
        "${RetentionSettings.DEFAULT_WEBHOOK_DEDUP_WINDOW_SECONDS}",
    )
    assertContains(
        row("RUNLINE_WEBHOOK_DEDUP_WINDOW_SECONDS"),
        "at least ${RetentionSettings.MIN_WEBHOOK_DEDUP_WINDOW_SECONDS}",
    )
    assertContains(
        row("RUNLINE_CRON_FIRING_RETENTION_SECONDS"),
        "${RetentionSettings.DEFAULT_CRON_FIRING_SECONDS}",
    )
    assertContains(
        row("RUNLINE_CRON_FIRING_RETENTION_SECONDS"),
        "at least ${RetentionSettings.MIN_CRON_FIRING_SECONDS}",
    )
    assertContains(
        row("RUNLINE_RETENTION_INTERVAL_SECONDS"),
        "${RetentionSettings.DEFAULT_INTERVAL_SECONDS}",
    )
    assertContains(
        row("RUNLINE_RETENTION_BATCH_SIZE"),
        "${RetentionSettings.DEFAULT_BATCH_SIZE}",
    )
  }
}
