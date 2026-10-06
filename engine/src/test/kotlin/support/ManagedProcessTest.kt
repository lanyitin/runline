package dev.lawlan.runline.engine.support

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.junit.jupiter.api.Timeout

/**
 * The limits on waiting for a process and the guarantee that it is gone afterwards, against real
 * processes: one that never ends and one that ignores the request to end.
 */
@Timeout(60, unit = TimeUnit.SECONDS)
class ManagedProcessTest {
  private val dir: Path = Files.createTempDirectory("managed-process")
  private val started = mutableListOf<ManagedProcess>()

  private fun start(script: String, name: String = "a test process"): ManagedProcess =
      ManagedProcess.start(
              name,
              listOf("sh", "-c", script),
              dir.resolve("p${started.size}.log"),
              terminationGrace = Duration.ofSeconds(1),
          )
          .also { started += it }

  /** A process that has said it is running, then does nothing for ten minutes. */
  private fun never() = start("echo up-and-waiting; exec sleep 600", "the sleeper")

  /** A process that does not end when asked to: it ignores SIGTERM and keeps going. */
  private fun stubborn() =
      start("trap '' TERM; echo ignoring-term; while :; do sleep 1; done", "the stubborn one")

  private fun awaitOutput(p: ManagedProcess, text: String) {
    val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
    while (text !in p.output()) {
      check(System.nanoTime() < deadline) { "${p.description} never printed $text" }
      Thread.sleep(10)
    }
  }

  private fun gone(p: ManagedProcess) = !p.process.isAlive

  @AfterTest
  fun cleanUp() {
    started.forEach { it.process.destroyForcibly() }
  }

  @Test
  fun `waiting for a process that never ends fails at the limit with what was waited for and what it said`() {
    val p = never()
    awaitOutput(p, "up-and-waiting")
    val begun = System.nanoTime()

    val failure = assertFailsWith<AssertionError> { p.awaitExit(Duration.ofMillis(500)) }

    assertTrue(Duration.ofNanos(System.nanoTime() - begun) < Duration.ofSeconds(10))
    val message = failure.message!!
    assertTrue("the sleeper" in message, message)
    assertTrue("500 ms" in message, message)
    assertTrue("up-and-waiting" in message, "its output is part of the message: $message")
  }

  @Test
  fun `a process that ends is waited for no longer than it takes and its exit code is returned`() {
    val p = start("echo done; exit 3")

    assertEquals(3, p.awaitExit(Duration.ofSeconds(30)))
  }

  @Test
  fun `closing ends a process that is running`() {
    val p = never()
    awaitOutput(p, "up-and-waiting")

    p.close()

    assertTrue(gone(p), "the process is still alive after close")
  }

  @Test
  fun `closing ends a process that ignores the request to end`() {
    val p = stubborn()
    awaitOutput(p, "ignoring-term")

    p.close()

    assertTrue(gone(p), "a process that ignores SIGTERM must be killed")
  }

  @Test
  fun `closing does not leave the thread interrupted when it was not`() {
    val p = stubborn()
    awaitOutput(p, "ignoring-term")

    p.close()

    assertFalse(Thread.currentThread().isInterrupted, "killing a process is not an interruption")
  }

  @Test
  fun `closing ends the process even when the thread was interrupted, as it is by a timeout`() {
    val p = stubborn()
    awaitOutput(p, "ignoring-term")

    Thread.currentThread().interrupt()
    try {
      p.close()
      assertTrue(Thread.currentThread().isInterrupted, "the interruption is not swallowed")
    } finally {
      Thread.interrupted()
    }

    assertTrue(gone(p), "an interrupted close left the process alive")
  }

  @Test
  fun `the output tail is the end of what the process wrote`() {
    val p = start("i=1; while [ \$i -le 100 ]; do echo line-\$i; i=\$((i+1)); done")
    p.awaitExit(Duration.ofSeconds(30))

    val tail = p.outputTail(lines = 5)

    assertEquals((96..100).map { "line-$it" }, tail.lines())
  }
}
