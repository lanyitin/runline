package dev.lawlan.runline.runner

import dev.lawlan.runline.runner.support.TestJars
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

/**
 * What the host prints while the Runner calls back into it (a listener, a workspace observer) is
 * the host's output, not the pipeline's: only the pipeline's own output becomes the run's log.
 */
class RunnerHostCallbacksTest {
  @TempDir lateinit var tmp: Path

  private val runners = mutableListOf<Runner>()

  @AfterTest fun closeRunners() = runners.forEach { it.close() }

  @Test
  fun `output of host callbacks is not attributed to the run`() {
    val runner =
        Runner(
                Workspaces(
                    WorkspaceConfig(
                        tmp.resolve("shared"),
                        tmp.resolve("runs"),
                        10_000,
                        Duration.ofHours(1),
                    ),
                    Clock.systemUTC(),
                ) {
                  println("host observer: ${it.javaClass.simpleName}")
                },
                maxConcurrentRuns = 1,
            )
            .also { runners += it }
    val jar =
        TestJars.build(
            tmp,
            "talk.jar",
            mapOf(
                "P" to TestJars.pipeline("P", "talk", """System.out.println("pipeline says");""")
            ),
        )
    val lines = CopyOnWriteArrayList<String>()

    runner
        .start(RunRequest("run-1", jar, "P")) {
          if (it is RunEvent.StatusChanged) println("host listener: ${it.status}")
          if (it is RunEvent.LogLine) lines += it.line
        }
        .result
        .get(10, TimeUnit.SECONDS)

    assertEquals(listOf("pipeline says"), lines)
  }
}
