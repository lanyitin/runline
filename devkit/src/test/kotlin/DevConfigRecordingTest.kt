package dev.lawlan.runline.devkit

import dev.lawlan.runline.runner.RecordingOptions
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DevConfigRecordingTest {
  private val project = Path.of("/work/my-pipelines")

  @Test
  fun `recording is off unless asked for`() {
    assertNull(DevConfig.fromEnvironment(emptyMap(), project).recording)
  }

  @Test
  fun `RUNLINE_RECORD true turns it on with the default limit and directory`() {
    val recording =
        DevConfig.fromEnvironment(mapOf("RUNLINE_RECORD" to "true"), project).recording!!

    assertEquals(RecordingOptions(), recording.options)
    assertEquals(project.resolve(".runline/recordings"), recording.directory)
    assertEquals(".runline/recordings", recording.shownAs)
  }

  @Test
  fun `the event limit and the output directory can be set`() {
    val recording =
        DevConfig.fromEnvironment(
                mapOf(
                    "RUNLINE_RECORD" to "true",
                    "RUNLINE_RECORD_MAX_EVENTS" to "250",
                    "RUNLINE_RECORD_DIR" to "out/rec",
                ),
                project,
            )
            .recording!!

    assertEquals(250, recording.options.maxEvents)
    assertEquals(project.resolve("out/rec"), recording.directory)
    assertEquals("out/rec", recording.shownAs)
  }

  @Test
  fun `a limit of zero is allowed and keeps only the summary`() {
    val recording =
        DevConfig.fromEnvironment(
                mapOf("RUNLINE_RECORD" to "true", "RUNLINE_RECORD_MAX_EVENTS" to "0"),
                project,
            )
            .recording!!

    assertEquals(0, recording.options.maxEvents)
  }

  @Test
  fun `a value that is not true fails fast`() {
    assertFailsWith<IllegalStateException> {
      DevConfig.fromEnvironment(mapOf("RUNLINE_RECORD" to "yes"), project)
    }
  }

  @Test
  fun `a malformed limit fails fast`() {
    assertFailsWith<IllegalStateException> {
      DevConfig.fromEnvironment(
          mapOf("RUNLINE_RECORD" to "true", "RUNLINE_RECORD_MAX_EVENTS" to "-1"),
          project,
      )
    }
    assertFailsWith<IllegalStateException> {
      DevConfig.fromEnvironment(
          mapOf("RUNLINE_RECORD" to "true", "RUNLINE_RECORD_MAX_EVENTS" to "many"),
          project,
      )
    }
  }
}
