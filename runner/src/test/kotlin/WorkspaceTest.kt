package dev.lawlan.runline.runner

import dev.lawlan.runline.core.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class WorkspaceTest {
  @TempDir lateinit var tmp: Path

  private class TestClock(var now: Instant = Instant.parse("2026-10-03T00:00:00Z")) : Clock() {
    override fun getZone() = ZoneOffset.UTC

    override fun withZone(zone: java.time.ZoneId?) = this

    override fun instant(): Instant = now

    fun advance(d: Duration) {
      now = now.plus(d)
    }
  }

  private val clock = TestClock()
  private val events = mutableListOf<WorkspaceEvent>()
  private val retention = Duration.ofHours(1)

  private fun workspaces(maxBytes: Long = 1_000): Workspaces =
      Workspaces(
          WorkspaceConfig(
              sharedRoot = tmp.resolve("persistent"),
              runRoot = tmp.resolve("scratch"),
              maxBytesPerScope = maxBytes,
              failedRunRetention = retention,
          ),
          clock,
      ) {
        events += it
      }

  private fun context(w: RunWorkspace, name: String = "demo"): PipelineContext =
      RestrictedContext(
          PipelineMetadata(
              name = name,
              parameters = emptyList(),
              files =
                  mapOf(
                      FileScope.PIPELINE_SHARED to FileMode.READ_WRITE,
                      FileScope.RUN_PRIVATE to FileMode.READ_WRITE,
                  ),
              network = AccessPolicy.Allow(emptySet()),
              processes = AccessPolicy.Allow(emptySet()),
              resources = emptySet(),
          ),
          emptyMap(),
          w.sharedDir,
          w.runDir,
          w.maxBytesPerScope,
      )

  @Test
  fun `prepare creates the shared and private directories when missing`() {
    val w = workspaces().prepare("demo", "run-1")

    assertTrue(Files.isDirectory(w.sharedDir))
    assertTrue(Files.isDirectory(w.runDir))
    assertTrue(w.sharedDir.startsWith(tmp.resolve("persistent")))
    assertTrue(w.runDir.startsWith(tmp.resolve("scratch")))
  }

  @Test
  fun `prepare keeps existing content`() {
    val ws = workspaces()
    val first = ws.prepare("demo", "run-1")
    first.sharedDir.resolve("keep.txt").writeText("x")

    val second = ws.prepare("demo", "run-2")

    assertEquals("x", second.sharedDir.resolve("keep.txt").readText())
  }

  @Test
  fun `runs of the same pipeline share one directory`() {
    val ws = workspaces()
    val a = context(ws.prepare("demo", "run-1"))
    val b = context(ws.prepare("demo", "run-2"))

    a.files.writeText(FileScope.PIPELINE_SHARED, "cache.txt", "hello")

    assertEquals("hello", b.files.readText(FileScope.PIPELINE_SHARED, "cache.txt"))
  }

  @Test
  fun `different pipelines cannot see each other's shared directory`() {
    val ws = workspaces()
    val a = ws.prepare("alpha", "run-1")
    val b = ws.prepare("beta", "run-1")
    context(a, "alpha").files.writeText(FileScope.PIPELINE_SHARED, "secret.txt", "s")

    assertNotEquals(a.sharedDir, b.sharedDir)
    assertFalse(context(b, "beta").files.exists(FileScope.PIPELINE_SHARED, "secret.txt"))
    assertFailsWith<PipelineAccessDenied> {
      context(b, "beta").files.readText(FileScope.PIPELINE_SHARED, "../alpha/secret.txt")
    }
  }

  @Test
  fun `a run cannot reach another run's private directory`() {
    val ws = workspaces()
    val a = ws.prepare("demo", "run-1")
    val b = ws.prepare("demo", "run-2")
    context(a).files.writeText(FileScope.RUN_PRIVATE, "mine.txt", "a")

    assertNotEquals(a.runDir, b.runDir)
    assertFalse(context(b).files.exists(FileScope.RUN_PRIVATE, "mine.txt"))
    assertFailsWith<PipelineAccessDenied> {
      context(b).files.readText(FileScope.RUN_PRIVATE, "../run-1/mine.txt")
    }
  }

  @Test
  fun `a run id cannot be used twice within the same pipeline`() {
    val ws = workspaces()
    ws.prepare("demo", "run-1")

    assertFailsWith<IllegalArgumentException> { ws.prepare("demo", "run-1") }
    ws.prepare("other", "run-1")
  }

  @Test
  fun `a run id stays taken while its retained private directory still exists`() {
    val ws = workspaces()
    ws.prepare("demo", "run-1")
    ws.finish("demo", "run-1", RunOutcome.FAILED)

    assertFailsWith<IllegalArgumentException> { ws.prepare("demo", "run-1") }
  }

  @Test
  fun `names that could escape their directory are rejected`() {
    val ws = workspaces()

    for (bad in listOf("", ".", "..", "a/b", "../x", "a\\b", "a\u0000b")) {
      assertFailsWith<IllegalArgumentException>("pipeline '$bad'") { ws.prepare(bad, "run-1") }
      assertFailsWith<IllegalArgumentException>("run '$bad'") { ws.prepare("demo", bad) }
    }
  }

  @Test
  fun `successful run's private directory is removed immediately but shared stays`() {
    val ws = workspaces()
    val w = ws.prepare("demo", "run-1")
    w.runDir.resolve("tmp.txt").writeText("x")
    w.sharedDir.resolve("keep.txt").writeText("x")

    ws.finish("demo", "run-1", RunOutcome.SUCCEEDED)

    assertFalse(w.runDir.exists())
    assertTrue(w.sharedDir.resolve("keep.txt").exists())
  }

  @Test
  fun `removing a private directory does not follow symbolic links out of it`() {
    val ws = workspaces()
    val w = ws.prepare("demo", "run-1")
    val outside = tmp.resolve("outside").createDirectories()
    outside.resolve("precious.txt").writeText("p")
    w.runDir.resolve("link").createSymbolicLinkPointingTo(outside)

    ws.finish("demo", "run-1", RunOutcome.SUCCEEDED)

    assertFalse(w.runDir.exists())
    assertTrue(outside.resolve("precious.txt").exists())
  }

  @Test
  fun `failed, cancelled and interrupted runs keep their private directory until retention expires`() {
    val ws = workspaces()
    val outcomes = listOf(RunOutcome.FAILED, RunOutcome.CANCELLED, RunOutcome.INTERRUPTED)
    val dirs = outcomes.mapIndexed { i, outcome ->
      val w = ws.prepare("demo", "run-$i")
      w.runDir.resolve("evidence.txt").writeText("x")
      ws.finish("demo", "run-$i", outcome)
      w.runDir
    }

    assertTrue(dirs.all { it.exists() }, "kept right after the run ends")

    clock.advance(retention.minusSeconds(1))
    ws.sweep()
    assertTrue(dirs.all { it.exists() }, "kept before retention expires")

    clock.advance(Duration.ofSeconds(2))
    ws.sweep()
    assertTrue(dirs.none { it.exists() }, "removed after retention expires")
  }

  @Test
  fun `after a restart shared content survives and leftover private directories follow retention`() {
    val first = workspaces()
    val w = first.prepare("demo", "run-1")
    w.sharedDir.resolve("keep.txt").writeText("x")
    w.runDir.resolve("left.txt").writeText("x")
    Files.setLastModifiedTime(
        w.runDir,
        java.nio.file.attribute.FileTime.from(clock.instant()),
    )

    val restarted = workspaces()
    restarted.sweep()
    assertTrue(w.runDir.exists(), "fresh leftover is kept")

    clock.advance(retention.plusSeconds(1))
    restarted.sweep()
    assertFalse(w.runDir.exists(), "expired leftover is removed")
    assertEquals("x", w.sharedDir.resolve("keep.txt").readText())
  }

  @Test
  fun `sweep leaves private directories of runs still in progress`() {
    val ws = workspaces()
    val w = ws.prepare("demo", "run-1")
    clock.advance(retention.multipliedBy(10))

    ws.sweep()

    assertTrue(w.runDir.exists())
  }

  @Test
  fun `shared usage reports bytes and clearing empties the directory`() {
    val ws = workspaces()
    val w = ws.prepare("demo", "run-1")
    context(w)
        .files
        .writeText(
            FileScope.PIPELINE_SHARED,
            "d/a.txt",
            "12345".also { w.sharedDir.resolve("d").createDirectories() },
        )
    w.sharedDir.resolve("b.txt").writeText("123")

    assertEquals(8L, ws.sharedUsage("demo"))
    assertEquals(0L, ws.sharedUsage("never-ran"))

    ws.clearShared("demo")

    assertEquals(0L, ws.sharedUsage("demo"))
    assertTrue(Files.isDirectory(w.sharedDir), "directory itself stays usable")
  }

  @Test
  fun `writes beyond the configured limit are rejected through the context`() {
    val ws = workspaces(maxBytes = 10)
    val ctx = context(ws.prepare("demo", "run-1"))
    ctx.files.writeText(FileScope.PIPELINE_SHARED, "a.txt", "123456")

    assertFailsWith<FileQuotaExceeded> {
      ctx.files.writeText(FileScope.PIPELINE_SHARED, "b.txt", "123456")
    }
  }

  @Test
  fun `creation, removal and usage are reported to the observer`() {
    val ws = workspaces()
    val w = ws.prepare("demo", "run-1")
    w.sharedDir.resolve("a.txt").writeText("123")

    ws.sharedUsage("demo")
    ws.finish("demo", "run-1", RunOutcome.SUCCEEDED)
    ws.clearShared("demo")

    assertEquals(
        listOf(
            WorkspaceEvent.Created(FileScope.PIPELINE_SHARED, "demo", null),
            WorkspaceEvent.Created(FileScope.RUN_PRIVATE, "demo", "run-1"),
            WorkspaceEvent.SharedUsage("demo", 3L),
            WorkspaceEvent.Removed(FileScope.RUN_PRIVATE, "demo", "run-1"),
            WorkspaceEvent.Removed(FileScope.PIPELINE_SHARED, "demo", null),
            WorkspaceEvent.SharedUsage("demo", 0L),
        ),
        events,
    )
  }

  @Test
  fun `second prepare of an existing directory does not report creation again`() {
    val ws = workspaces()
    ws.prepare("demo", "run-1")
    events.clear()

    ws.prepare("demo", "run-2")

    assertEquals(
        listOf<WorkspaceEvent>(WorkspaceEvent.Created(FileScope.RUN_PRIVATE, "demo", "run-2")),
        events,
    )
  }
}
