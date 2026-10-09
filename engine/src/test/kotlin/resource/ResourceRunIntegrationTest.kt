package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.accessors.support.TestDirectories
import dev.lawlan.runline.engine.artifact.Visibility
import dev.lawlan.runline.engine.auth.ApiIdentity
import dev.lawlan.runline.engine.auth.Role
import dev.lawlan.runline.engine.run.CancelResult
import dev.lawlan.runline.engine.run.RunState
import dev.lawlan.runline.engine.support.RunHarness
import dev.lawlan.runline.engine.support.RunHarness.Companion.deafUntilReleased
import dev.lawlan.runline.engine.support.RunHarness.Companion.holdUntilReleased
import java.nio.file.Files
import java.time.Duration
import java.util.UUID
import kotlin.test.*

/**
 * Shared resources with everything real: the coordinator, the scheduler, the Runner with a class
 * loader per run, and a PostgreSQL database. Pipelines are compiled from source and uploaded.
 */
class ResourceRunIntegrationTest {
  private val harnesses = mutableListOf<RunHarness>()
  private val root = ApiIdentity("root", Role.ADMIN)

  private fun harness(
      maxConcurrent: Int = 3,
      resourceWaitTimeout: Duration = Duration.ofHours(1),
      runTimeout: Duration? = null,
      jarDirectory: java.nio.file.Path? = null,
  ) =
      RunHarness(
              maxConcurrent = maxConcurrent,
              resourceWaitTimeout = resourceWaitTimeout,
              runTimeout = runTimeout,
              jarDirectory = jarDirectory,
          )
          .also { harnesses += it }

  @AfterTest fun closeAll() = harnesses.forEach { it.close() }

  private fun using(vararg names: String) =
      RunHarness.DEFAULT_DECLARATION + ", resources = {" + names.joinToString { "\"$it\"" } + "}"

  private fun RunHarness.holders(resource: String): List<UUID> =
      coordinator!!.activity(resource).holders.map { it.runId }

  private fun RunHarness.waiters(resource: String): List<UUID> =
      coordinator!!.activity(resource).waiters.map { it.runId }

  private fun RunHarness.release(pipeline: String) =
      Files.writeString(shared(pipeline, "release"), "x")

  private fun RunHarness.read(pipeline: String, file: String) =
      Files.readString(shared(pipeline, file))

  private fun RunHarness.exists(pipeline: String, file: String) =
      Files.exists(shared(pipeline, file))

  private val loaderId = "String.valueOf(System.identityHashCode(getClass().getClassLoader()))"

  // ---- mutual exclusion across class loaders ----

  @Test
  fun `two runs in different class loaders never hold a capacity one resource together`() {
    val h = harness(maxConcurrent = 2)
    h.defineResource("lemonade", 1)
    // Two versions of one pipeline: two jars, so two run class loaders that cannot see each other.
    val first =
        h.upload(
            "excl",
            """
            context.getFiles().writeText(FileScope.PIPELINE_SHARED, "a-loader", $loaderId);
            ${holdUntilReleased("a-started")}
            context.getFiles().writeText(FileScope.PIPELINE_SHARED, "a-ended", "x");
            """
                .trimIndent(),
            declaration = using("lemonade"),
        )
    val second =
        h.upload(
            "excl",
            """
            context.getFiles().writeText(FileScope.PIPELINE_SHARED, "b-saw-a-ended",
                String.valueOf(context.getFiles().exists(FileScope.PIPELINE_SHARED, "a-ended")));
            context.getFiles().writeText(FileScope.PIPELINE_SHARED, "b-loader", $loaderId);
            """
                .trimIndent(),
            declaration = using("lemonade"),
        )
    val a = h.start(first, "excl")
    h.awaitFile(h.shared("excl", "a-started"))

    val b = h.start(second, "excl")

    h.await(b, RunState.WAITING_FOR_RESOURCES)
    assertEquals(RunState.RUNNING, h.state(a))
    assertEquals(listOf(a), h.holders("lemonade"))
    assertEquals(listOf(b), h.waiters("lemonade"))
    assertFalse(h.exists("excl", "b-loader"), "the second run must not have started")
    h.release("excl")
    h.await(a, RunState.SUCCEEDED)
    h.await(b, RunState.SUCCEEDED)
    assertEquals(
        "true",
        h.read("excl", "b-saw-a-ended"),
        "the second run began after the first ended",
    )
    assertNotEquals(
        h.read("excl", "a-loader"),
        h.read("excl", "b-loader"),
        "the runs must have run in different class loaders",
    )
    assertEquals(emptyList(), h.holders("lemonade"))
  }

  @Test
  fun `a resource of capacity two lets two runs in and holds the third`() {
    val h = harness(maxConcurrent = 4)
    h.defineResource("gpu", 2)
    val one = h.upload("one", holdUntilReleased("one-started"), declaration = using("gpu"))
    val two = h.upload("two", holdUntilReleased("two-started"), declaration = using("gpu"))
    val three = h.upload("three", "", declaration = using("gpu"))
    val r1 = h.start(one, "one")
    val r2 = h.start(two, "two")
    h.awaitFile(h.shared("one", "one-started"))
    h.awaitFile(h.shared("two", "two-started"))

    val r3 = h.start(three, "three")

    h.await(r3, RunState.WAITING_FOR_RESOURCES)
    assertEquals(setOf(r1, r2), h.holders("gpu").toSet())
    h.release("one")
    h.await(r1, RunState.SUCCEEDED)
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(r3).state)
    h.release("two")
  }

  @Test
  fun `waiting runs start in the order they were accepted`() {
    val h = harness(maxConcurrent = 5)
    h.defineResource("lemonade", 1)
    val holder =
        h.upload("holder", holdUntilReleased("holder-started"), declaration = using("lemonade"))
    val names = listOf("w1", "w2", "w3")
    val hashes = names.map { h.upload(it, "", declaration = using("lemonade")) }
    val first = h.start(holder, "holder")
    h.awaitFile(h.shared("holder", "holder-started"))
    val waiting =
        names.zip(hashes).map { (n, hash) ->
          h.start(hash, n).also { h.await(it, RunState.WAITING_FOR_RESOURCES) }
        }
    assertEquals(waiting, h.waiters("lemonade"))

    h.release("holder")

    h.await(first, RunState.SUCCEEDED)
    val ended = waiting.map { h.awaitEnd(it) }
    assertEquals(List(3) { RunState.SUCCEEDED }, ended.map { it.state })
    assertEquals(ended.sortedBy { it.startedAt }.map { it.id }, waiting)
  }

  // ---- waiting holds no slot ----

  @Test
  fun `a run that waits for a resource holds no slot, so an unrelated run starts at once`() {
    val h = harness(maxConcurrent = 2)
    h.defineResource("lemonade", 1)
    val holder =
        h.upload("holder", holdUntilReleased("holder-started"), declaration = using("lemonade"))
    val needs = h.upload("needs", "", declaration = using("lemonade"))
    val unrelated = h.upload("unrelated")
    h.start(holder, "holder")
    h.awaitFile(h.shared("holder", "holder-started"))
    val waiting = h.start(needs, "needs")
    h.await(waiting, RunState.WAITING_FOR_RESOURCES)

    val other = h.start(unrelated, "unrelated")

    assertEquals(RunState.SUCCEEDED, h.awaitEnd(other).state)
    assertEquals(RunState.WAITING_FOR_RESOURCES, h.state(waiting))
    h.release("holder")
    h.awaitEnd(waiting)
  }

  // ---- the end of a run always frees its resources ----

  @Test
  fun `a run that fails frees its resource for the next`() {
    val h = harness()
    h.defineResource("lemonade", 1)
    val failing =
        h.upload(
            "failing",
            """
            ${holdUntilReleased("failing-started")}
            throw new RuntimeException("boom");
            """
                .trimIndent(),
            declaration = using("lemonade"),
        )
    val next = h.upload("next", "", declaration = using("lemonade"))
    val first = h.start(failing, "failing")
    h.awaitFile(h.shared("failing", "failing-started"))
    val second = h.start(next, "next")
    h.await(second, RunState.WAITING_FOR_RESOURCES)

    h.release("failing")

    assertEquals(RunState.FAILED, h.awaitEnd(first).state)
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(second).state)
    assertEquals(emptyList(), h.holders("lemonade"))
  }

  @Test
  fun `a run that is cancelled while running frees its resource for the next`() {
    val h = harness()
    h.defineResource("lemonade", 1)
    val long = h.upload("long", RunHarness.WAIT_FOR_STOP, declaration = using("lemonade"))
    val next = h.upload("next", "", declaration = using("lemonade"))
    val first = h.start(long, "long")
    h.await(first, RunState.RUNNING)
    val second = h.start(next, "next")
    h.await(second, RunState.WAITING_FOR_RESOURCES)

    assertEquals(CancelResult.CancellationRequested, h.service.cancel(first, Visibility.All))

    assertEquals(RunState.CANCELLED, h.awaitEnd(first).state)
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(second).state)
    assertEquals(emptyList(), h.holders("lemonade"))
  }

  @Test
  fun `a run that cannot even be started frees what it was granted`() {
    val missing = TestDirectories.forThisTest("jars").resolve("does-not-exist")
    val h = harness(jarDirectory = missing)
    h.defineResource("lemonade", 1)
    val hash = h.upload("p", "", declaration = using("lemonade"))

    val id = h.start(hash, "p")

    assertEquals(RunState.FAILED, h.awaitEnd(id).state)
    assertEquals(emptyList(), h.holders("lemonade"))
    assertEquals(emptyList(), h.waiters("lemonade"))
  }

  @Test
  fun `a run cancelled while waiting leaves the queue and never gets the resource`() {
    val h = harness()
    h.defineResource("lemonade", 1)
    val holder =
        h.upload("holder", holdUntilReleased("holder-started"), declaration = using("lemonade"))
    val needs =
        h.upload(
            "needs",
            "context.getFiles().writeText(FileScope.PIPELINE_SHARED, \"ran\", \"x\");",
            declaration = using("lemonade"),
        )
    val first = h.start(holder, "holder")
    h.awaitFile(h.shared("holder", "holder-started"))
    val waiting = h.start(needs, "needs")
    h.await(waiting, RunState.WAITING_FOR_RESOURCES)

    assertEquals(CancelResult.Cancelled, h.service.cancel(waiting, Visibility.All))

    assertEquals(RunState.CANCELLED, h.state(waiting))
    assertEquals(emptyList(), h.waiters("lemonade"))
    h.release("holder")
    h.await(first, RunState.SUCCEEDED)
    assertEquals(emptyList(), h.holders("lemonade"))
    assertFalse(h.exists("needs", "ran"))
  }

  @Test
  fun `closing the scheduler frees everything runs held or waited for`() {
    val h = harness(maxConcurrent = 2)
    h.defineResource("lemonade", 1)
    val long = h.upload("long", RunHarness.WAIT_FOR_STOP, declaration = using("lemonade"))
    val running = h.start(long, "long")
    h.await(running, RunState.RUNNING)
    val waiting = h.start(long, "long")
    h.await(waiting, RunState.WAITING_FOR_RESOURCES)

    h.scheduler.close()

    assertEquals(RunState.INTERRUPTED, h.state(running))
    assertEquals(RunState.INTERRUPTED, h.state(waiting))
    assertEquals(emptyList(), h.holders("lemonade"))
    assertEquals(emptyList(), h.waiters("lemonade"))
  }

  // ---- limits on waiting ----

  @Test
  fun `a run that waits longer than the limit fails saying the wait timed out and never starts`() {
    val h = harness(resourceWaitTimeout = Duration.ofMillis(400))
    h.defineResource("lemonade", 1)
    val holder =
        h.upload("holder", holdUntilReleased("holder-started"), declaration = using("lemonade"))
    val needs =
        h.upload(
            "needs",
            "context.getFiles().writeText(FileScope.PIPELINE_SHARED, \"ran\", \"x\");",
            declaration = using("lemonade"),
        )
    val first = h.start(holder, "holder")
    h.awaitFile(h.shared("holder", "holder-started"))

    val waiting = h.start(needs, "needs")

    val ended = h.awaitEnd(waiting)
    assertEquals(RunState.FAILED, ended.state)
    assertEquals(ResourceFailures.WAIT_TIMEOUT, ended.failure!!.type)
    assertTrue(ended.failure!!.message!!.contains("等待資源逾時"), ended.failure!!.message)
    assertNull(ended.startedAt)
    assertFalse(h.exists("needs", "ran"))
    assertEquals(RunState.RUNNING, h.state(first), "the holder is not touched")
    assertEquals(listOf(first), h.holders("lemonade"))
    h.release("holder")
  }

  @Test
  fun `a holder that outlived its timeout keeps the resource until it ends`() {
    val h = harness(runTimeout = Duration.ofMillis(300))
    h.defineResource("lemonade", 1)
    val stuck =
        h.upload("stuck", deafUntilReleased("stuck-started"), declaration = using("lemonade"))
    val next = h.upload("next", "", declaration = using("lemonade"))
    val first = h.start(stuck, "stuck")
    h.await(first, RunState.TIMED_OUT_UNFINISHED)

    val second = h.start(next, "next")

    h.await(second, RunState.WAITING_FOR_RESOURCES)
    Thread.sleep(500)
    assertEquals(RunState.WAITING_FOR_RESOURCES, h.state(second))
    assertEquals(listOf(first), h.holders("lemonade"))
    h.release("stuck")
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(first).state, "it finished, late but fine")
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(second).state)
  }

  // ---- forced release ----

  @Test
  fun `forcing a stuck holder to let go lets the next run in while the stuck run is left running`() {
    val h = harness(runTimeout = Duration.ofMillis(300))
    h.defineResource("lemonade", 1)
    val stuck =
        h.upload("stuck", deafUntilReleased("stuck-started"), declaration = using("lemonade"))
    val next = h.upload("next", "", declaration = using("lemonade"))
    val first = h.start(stuck, "stuck")
    h.await(first, RunState.TIMED_OUT_UNFINISHED)
    val second = h.start(next, "next")
    h.await(second, RunState.WAITING_FOR_RESOURCES)

    val result = h.coordinator!!.forceRelease("lemonade", first, root)

    assertIs<ForceReleaseResult.Released>(result)
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(second).state)
    assertEquals(RunState.TIMED_OUT_UNFINISHED, h.state(first), "the stuck run is not stopped")
    h.release("stuck")
    h.awaitEnd(first)
    assertEquals(emptyList(), h.holders("lemonade"))
  }

  @Test
  fun `a capacity raised by an administrator lets a waiting run in without waiting for an end`() {
    val h = harness()
    h.defineResource("lemonade", 1)
    val holder =
        h.upload("holder", holdUntilReleased("holder-started"), declaration = using("lemonade"))
    val needs = h.upload("needs", "", declaration = using("lemonade"))
    val first = h.start(holder, "holder")
    h.awaitFile(h.shared("holder", "holder-started"))
    val waiting = h.start(needs, "needs")
    h.await(waiting, RunState.WAITING_FOR_RESOURCES)

    h.resourceAdmin.update("lemonade", 2, null, root)

    assertEquals(RunState.SUCCEEDED, h.awaitEnd(waiting).state)
    h.release("holder")
    h.await(first, RunState.SUCCEEDED)
  }

  @Test
  fun `a resource disabled while a run waits fails that run as unavailable`() {
    val h = harness()
    h.defineResource("lemonade", 1)
    val holder =
        h.upload("holder", holdUntilReleased("holder-started"), declaration = using("lemonade"))
    val needs = h.upload("needs", "", declaration = using("lemonade"))
    h.start(holder, "holder")
    h.awaitFile(h.shared("holder", "holder-started"))
    val waiting = h.start(needs, "needs")
    h.await(waiting, RunState.WAITING_FOR_RESOURCES)

    h.resourceAdmin.update("lemonade", null, false, root)

    val ended = h.awaitEnd(waiting)
    assertEquals(RunState.FAILED, ended.state)
    assertEquals(ResourceFailures.UNAVAILABLE, ended.failure!!.type)
    assertTrue(ended.failure!!.message!!.contains("停用"))
    h.release("holder")
  }
}
