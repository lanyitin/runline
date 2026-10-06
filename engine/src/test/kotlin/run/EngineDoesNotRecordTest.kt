package dev.lawlan.runline.engine.run

import dev.lawlan.runline.engine.support.RunHarness
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Recording is the development entry's. The Engine's runs go through the same Runner but have no
 * way to ask for it: their declared limits are enforced exactly as before.
 */
class EngineDoesNotRecordTest {
  private val harnesses = mutableListOf<RunHarness>()

  @AfterTest fun closeAll() = harnesses.forEach { it.close() }

  @Test
  fun `an Engine run enforces the declared limits instead of recording`() {
    val h = RunHarness().also { harnesses += it }
    val hash =
        h.upload(
            "undeclared",
            """context.getProcesses().run(java.util.List.of("echo", "hi"));""",
        )

    val id = h.start(hash, "undeclared")

    val ended = h.awaitEnd(id)
    assertEquals(RunState.FAILED, ended.state)
    assertTrue("PipelineAccessDenied" in ended.failure!!.type, ended.failure.toString())
  }

  @Test
  fun `an Engine run does not leave a recording or ask for one`() {
    val h = RunHarness().also { harnesses += it }
    val hash = h.upload("plain", """System.out.println("hello");""")

    val id = h.start(hash, "plain")

    assertEquals(RunState.SUCCEEDED, h.awaitEnd(id).state)
    assertEquals(listOf("hello"), h.runStore.read(id, 0, 10).map { it.line })
  }

  @Test
  fun `the Engine's sources have no way to turn recording on`() {
    val sources = Path.of(System.getProperty("runline.engineSources"))

    val mentions =
        Files.walk(sources).use { paths ->
          paths
              .filter { it.toString().endsWith(".kt") }
              .filter {
                val text = Files.readString(it)
                "RecordingOptions" in text || "RecordedIo" in text || "recording =" in text
              }
              .map { sources.relativize(it).toString() }
              .toList()
        }

    assertTrue(mentions.isEmpty(), "Engine sources refer to recording: $mentions")
    assertFalse(sources.toString().isEmpty())
  }
}
