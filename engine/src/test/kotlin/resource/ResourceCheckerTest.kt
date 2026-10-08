package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.engine.auth.ApiIdentity
import dev.lawlan.runline.engine.auth.Role
import dev.lawlan.runline.engine.config.ResourceSettings
import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.support.CapturedLogs
import dev.lawlan.runline.engine.support.migratedDatabase
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.writeText
import kotlin.test.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The check of a resource's entity: a real PostgreSQL database and the real file system, including
 * a named pipe nobody is at the other end of, which really blocks (WI-43).
 */
class ResourceCheckerTest {
  private val root: Path = Files.createTempDirectory("check-root")
  private val store = PostgresResourceStore(dataSourceOf(migratedDatabase()))
  private val behaviors =
      ResourceBehaviors.forEngine(ResourceSettings(root, Duration.ofSeconds(1), 1024L * 1024))
  private val admin = ResourceAdmin(store, Clock.systemUTC(), behaviors) {}
  private val ops = ApiIdentity("ops", Role.ADMIN)
  private val reader = InMemoryMetricReader.create()
  private val otel =
      OpenTelemetrySdk.builder()
          .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(reader).build())
          .build()
  private val logs = CapturedLogs()
  private val unlock = mutableListOf<Path>()
  private val permissions = mutableListOf<Path>()

  private fun checker(timeout: Duration = Duration.ofMillis(800)) =
      ResourceChecker(store, behaviors, Clock.systemUTC(), timeout, ResourceTelemetry(otel))

  private val checkers = mutableListOf<ResourceChecker>()

  private fun newChecker(timeout: Duration = Duration.ofMillis(800)) =
      checker(timeout).also { checkers += it }

  @AfterTest
  fun cleanUp() {
    // A named pipe with a reader stuck on it is let go by becoming its writer.
    unlock.forEach { pipe ->
      runCatching {
        val done = Executors.newSingleThreadExecutor()
        done
            .submit { FileChannel.open(pipe, StandardOpenOption.WRITE).close() }
            .get(5, TimeUnit.SECONDS)
        done.shutdown()
      }
    }
    permissions.forEach {
      Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwxrwxrwx"))
    }
    checkers.forEach { it.close() }
    logs.close()
  }

  private fun path(value: String) = JsonObject(mapOf("path" to JsonPrimitive(value)))

  private fun defineFile(name: String, path: String) =
      assertIs<CreateResourceResult.Created>(admin.create(name, 1, ops, "file", path(path)))

  private fun outcome(name: String, checker: ResourceChecker = newChecker()) =
      assertIs<CheckOutcome.Done>(checker.check(name, ops)).result

  private fun makePipe(path: Path) {
    val made = ProcessBuilder("mkfifo", path.toString()).redirectErrorStream(true).start()
    val said = made.inputStream.readAllBytes().decodeToString()
    check(made.waitFor() == 0) {
      "this platform cannot make a named pipe here, so the timeout is NOT verified: $said"
    }
    unlock.add(path)
  }

  @Test
  fun `a resource that does not exist is not found`() {
    assertEquals(CheckOutcome.NotFound, newChecker().check("nothing", ops))
  }

  @Test
  fun `a counter has no entity and passes`() {
    admin.create("gate", 1, ops)

    val result = outcome("gate")

    assertTrue(result.ok)
    assertNull(result.failure)
  }

  @Test
  fun `a file resource whose file can be used passes`() {
    defineFile("log", "logs/out.txt")

    assertTrue(outcome("log").ok)
  }

  @Test
  fun `each way the file cannot be used has its own category`() {
    defineFile("a", "a/out.txt")
    defineFile("b", "b/out.txt")
    defineFile("c", "c.txt")
    defineFile("d", "d/x.txt")
    root.resolve("b").writeText("a file where the directory should be")
    root.resolve("c.txt").writeText("x").also {
      Files.setPosixFilePermissions(
          root.resolve("c.txt"),
          PosixFilePermissions.fromString("r--r--r--"),
      )
      permissions.add(root.resolve("c.txt"))
    }
    check(!Files.isWritable(root.resolve("c.txt"))) { "permissions are not enforced for this user" }
    val outside = Files.createTempDirectory("check-outside")
    root.resolve("d").createSymbolicLinkPointingTo(outside)

    assertEquals(CheckFailure.PARENT_NOT_CREATABLE, outcome("b").failure)
    assertEquals(CheckFailure.NOT_READABLE_WRITABLE, outcome("c").failure)
    assertEquals(CheckFailure.PATH_OUTSIDE_ROOT, outcome("d").failure)
    val moved = root.resolveSibling(root.fileName.toString() + "-moved")
    Files.move(root, moved)
    try {
      assertEquals(CheckFailure.ROOT_UNAVAILABLE, outcome("a").failure)
    } finally {
      Files.move(moved, root)
    }
  }

  @Test
  fun `a check that blocks fails as a timeout when the limit is reached, and does not wait longer`() {
    defineFile("pipe", "pipe")
    makePipe(root.resolve("pipe"))

    val started = System.nanoTime()
    val result = outcome("pipe", newChecker(Duration.ofMillis(500)))
    val tookMs = Duration.ofNanos(System.nanoTime() - started).toMillis()

    assertFalse(result.ok)
    assertEquals(CheckFailure.TIMEOUT, result.failure)
    assertTrue(tookMs in 400..5_000, "took $tookMs ms for a limit of 500")
  }

  @Test
  fun `checks of one resource at the same time give one answer and use one thread`() {
    defineFile("pipe", "pipe")
    makePipe(root.resolve("pipe"))
    val checker = newChecker(Duration.ofMillis(700))
    val threadsBefore = checkThreads()
    val pool = Executors.newFixedThreadPool(6)
    try {
      val answers =
          (1..6)
              .map { pool.submit<CheckResult> { outcome("pipe", checker) } }
              .map { it.get(30, TimeUnit.SECONDS) }

      assertEquals(1, answers.toSet().size, "everybody got the same answer: $answers")
      assertEquals(CheckFailure.TIMEOUT, answers.first().failure)
      assertEquals(1, checkThreads() - threadsBefore, "one blocked thread, not one per check")
    } finally {
      pool.shutdownNow()
    }
  }

  @Test
  fun `a resource whose last check is still blocked answers as a timeout at once and starts nothing new`() {
    defineFile("pipe", "pipe")
    makePipe(root.resolve("pipe"))
    val checker = newChecker(Duration.ofMillis(300))
    outcome("pipe", checker)
    val before = checkThreads()

    val started = System.nanoTime()
    val again = outcome("pipe", checker)
    val tookMs = Duration.ofNanos(System.nanoTime() - started).toMillis()

    assertEquals(CheckFailure.TIMEOUT, again.failure)
    assertTrue(tookMs < 250, "answered in $tookMs ms")
    assertEquals(before, checkThreads(), "no thread was started for it")
  }

  private fun checkThreads() =
      Thread.getAllStackTraces().keys.count { it.name.startsWith("resource-check") && it.isAlive }

  @Test
  fun `the result and its time are kept on the resource, and a disabled resource can be checked`() {
    defineFile("log", "out.txt")
    admin.update("log", null, false, ops)

    val result = outcome("log")

    assertTrue(result.ok)
    assertEquals(result, store.find("log")!!.lastCheck)
  }

  @Test
  fun `a check answers the time the database keeps, also from a clock finer than a microsecond`() {
    defineFile("log", "out.txt")
    val finer = Clock.fixed(Instant.parse("2026-10-08T01:02:03.123456789Z"), ZoneOffset.UTC)
    val checker =
        ResourceChecker(store, behaviors, finer, Duration.ofMillis(800), ResourceTelemetry(otel))
            .also { checkers += it }

    val result = outcome("log", checker)

    assertEquals(Instant.parse("2026-10-08T01:02:03.123456Z"), result.checkedAt)
    assertEquals(result, store.find("log")!!.lastCheck)
  }

  @Test
  fun `a check takes no capacity and does not disturb who holds or waits`() {
    defineFile("log", "out.txt")
    val coordinator =
        ResourceCoordinator(
            ResourceAvailability(store),
            Clock.systemUTC(),
            Duration.ofHours(1),
            ResourceTelemetry(otel),
        )
    try {
      val holder = java.util.UUID.randomUUID()
      val waiter = java.util.UUID.randomUUID()
      coordinator.tryAcquire(dev.lawlan.runline.engine.run.PendingRun(holder, "a", listOf("log")))
      coordinator.tryAcquire(dev.lawlan.runline.engine.run.PendingRun(waiter, "b", listOf("log")))

      assertTrue(outcome("log").ok)

      val activity = coordinator.activity("log")
      assertEquals(listOf(holder), activity.holders.map { it.runId })
      assertEquals(listOf(waiter), activity.waiters.map { it.runId })
    } finally {
      coordinator.close()
    }
  }

  @Test
  fun `the check leaves the administrator's name and the result in the log and counts in metrics`() {
    defineFile("log", "out.txt")
    defineFile("bad", "x/y.txt")
    root.resolve("x").writeText("blocks the directory")

    outcome("log")
    outcome("bad")

    val lines = logs.lines.filter { it.contains("checked by") }
    assertTrue(
        lines.any { it.contains("log") && it.contains("ops") && it.contains("ok") },
        "$lines",
    )
    assertTrue(
        lines.any {
          it.contains("bad") && it.contains("ops") && it.contains("parent_not_creatable")
        },
        "$lines",
    )
    val metrics = reader.collectAllMetrics()
    fun total(name: String, resource: String) =
        metrics
            .firstOrNull { it.name == name }
            ?.longSumData
            ?.points
            ?.filter { p -> p.attributes.asMap().entries.any { it.value == resource } }
            ?.sumOf { it.value }
    assertEquals(1L, total("runline.resources.checks", "log"))
    assertEquals(1L, total("runline.resources.checks", "bad"))
    assertEquals(1L, total("runline.resources.check.failures", "bad"))
    assertEquals(0L, total("runline.resources.check.failures", "log"))
    val labels =
        metrics
            .first { it.name == "runline.resources.checks" }
            .longSumData
            .points
            .flatMap { it.attributes.asMap().keys.map { k -> k.key } }
            .toSet()
    assertEquals(
        setOf("resource", "type"),
        labels,
        "labels are the name and the type, nothing else",
    )
  }
}
