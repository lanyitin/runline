package dev.lawlan.runline.engine.run

import dev.lawlan.runline.engine.artifact.Visibility
import dev.lawlan.runline.engine.fixtures.DefaultsPipeline
import dev.lawlan.runline.engine.resource.ResourceProblem
import dev.lawlan.runline.engine.resource.ResourceProblemKind
import dev.lawlan.runline.engine.support.RunHarness
import dev.lawlan.runline.engine.support.RunHarness.Companion.WAIT_FOR_STOP
import dev.lawlan.runline.engine.support.RunHarness.Companion.deafUntilReleased
import dev.lawlan.runline.engine.support.RunHarness.Companion.holdUntilReleased
import dev.lawlan.runline.runner.WorkspaceEvent
import java.nio.file.Files
import java.time.Duration
import java.util.UUID
import kotlin.test.*

class RunServiceTest {
  private val harnesses = mutableListOf<RunHarness>()

  private fun harness(
      maxConcurrent: Int = 2,
      runTimeout: Duration? = null,
      gate: ResourceGate = NoResources,
      jarDirectory: java.nio.file.Path? = null,
      shutdownGrace: Duration = Duration.ofSeconds(5),
  ) =
      RunHarness(
              maxConcurrent = maxConcurrent,
              runTimeout = runTimeout,
              gate = gate,
              jarDirectory = jarDirectory,
              shutdownGrace = shutdownGrace,
          )
          .also { harnesses += it }

  @AfterTest fun closeAll() = harnesses.forEach { it.close() }

  private fun RunHarness.release(pipeline: String) =
      Files.writeString(shared(pipeline, "release"), "x")

  // ---- creating a run ----

  @Test
  fun `a run is accepted as queued, runs, and ends with its result and log`() {
    val h = harness()
    val hash =
        h.upload(
            "hello",
            """System.out.println("hello " + context.getParameters().get("who"));""",
            declaration =
                RunHarness.DEFAULT_DECLARATION + ", parameters = {@Param(name = \"who\")}",
        )

    val accepted = h.create(hash, "hello", mapOf("who" to "world"))

    val run = (accepted as CreateRunResult.Accepted).run
    assertEquals(RunState.QUEUED, run.state)
    assertEquals(RunSource.Manual("alice"), run.source)
    val ended = h.awaitEnd(run.id)
    assertEquals(RunState.SUCCEEDED, ended.state)
    assertNotNull(ended.startedAt)
    assertNotNull(ended.finishedAt)
    assertNull(ended.failure)
    assertEquals(
        listOf("hello world"),
        h.runStore.read(run.id, 0, 100).map { it.line },
    )
  }

  @Test
  fun `the run receives the supplied parameters and the defaults of omitted optionals`() {
    val h = harness()
    val hash = h.uploadKotlin(DefaultsPipeline::class.java)

    val id = h.start(hash, "defaults", mapOf("env" to "prod"))

    h.await(id, RunState.SUCCEEDED)
    assertEquals("prod/3", Files.readString(h.shared("defaults", "seen")))
    assertEquals(mapOf("env" to "prod", "retries" to "3"), h.record(id).parameters)
  }

  @Test
  fun `a definition that does not exist is refused and leaves no run`() {
    val h = harness()
    val hash = h.upload("real")

    assertEquals(CreateRunResult.DefinitionNotFound, h.create(hash, "other"))
    assertEquals(CreateRunResult.DefinitionNotFound, h.create("f".repeat(64), "real"))
    assertEquals(0, h.runCount())
  }

  @Test
  fun `a definition the requester may not see is reported as not found`() {
    val h = harness()
    val hash = h.upload("bobs", uploader = "bob")

    val asAlice = h.create(hash, "bobs", visibility = Visibility.OwnedBy("alice"))
    val asBob = h.create(hash, "bobs", visibility = Visibility.OwnedBy("bob"))
    val asAdmin = h.create(hash, "bobs", visibility = Visibility.All)

    assertEquals(CreateRunResult.DefinitionNotFound, asAlice)
    assertIs<CreateRunResult.Accepted>(asBob)
    assertIs<CreateRunResult.Accepted>(asAdmin)
    assertEquals(2, h.runCount())
  }

  @Test
  fun `any version of a pipeline the requester uploaded can be run`() {
    val h = harness()
    val old = h.upload("tool", "System.out.println(\"v1\");")
    val new = h.upload("tool", "System.out.println(\"v2\");")

    val first = h.start(old, "tool")
    val second = h.start(new, "tool")

    h.await(first, RunState.SUCCEEDED)
    h.await(second, RunState.SUCCEEDED)
    assertEquals(listOf("v1"), h.runStore.read(first, 0, 10).map { it.line })
    assertEquals(listOf("v2"), h.runStore.read(second, 0, 10).map { it.line })
  }

  @Test
  fun `invalid parameters are refused naming the parameter and leave no run`() {
    val h = harness()
    val hash =
        h.upload(
            "needs",
            declaration =
                RunHarness.DEFAULT_DECLARATION + ", parameters = {@Param(name = \"env\")}",
        )

    val result = h.create(hash, "needs", mapOf("colour" to "red"))

    assertEquals(
        CreateRunResult.InvalidParameters(
            listOf(
                ParameterProblem("env", ParameterProblemKind.MISSING),
                ParameterProblem("colour", ParameterProblemKind.UNDECLARED),
            )
        ),
        result,
    )
    assertEquals(0, h.runCount())
  }

  // ---- unsafe ----

  private val unsafeBody = "java.nio.file.Files.exists(java.nio.file.Path.of(\"x\"));"

  @Test
  fun `an unsafe pipeline is refused unless its definition allows it, and leaves no run`() {
    val h = harness()
    val hash = h.upload("risky", unsafeBody)

    val result = h.create(hash, "risky")

    assertEquals(CreateRunResult.UnsafeNotAllowed("risky"), result)
    assertEquals(0, h.runCount())
  }

  @Test
  fun `an unsafe pipeline runs once allowed and the run records the setting and who set it`() {
    val h = harness()
    val hash = h.upload("risky", unsafeBody)
    val set =
        h.definitions.setUnsafeExecution(
            hash,
            "alice",
            "risky",
            true,
            "root",
            java.time.Instant.now(),
        )!!

    val id = h.start(hash, "risky")

    val run = h.await(id, RunState.SUCCEEDED)
    assertEquals(UnsafeExecution("root", set.unsafeSettingSetAt!!), run.unsafeExecution)
  }

  @Test
  fun `withdrawing the permission stops new unsafe runs but not the setting snapshot of old ones`() {
    val h = harness()
    val hash = h.upload("risky", unsafeBody)
    h.definitions.setUnsafeExecution(hash, "alice", "risky", true, "root", java.time.Instant.now())
    val first = h.start(hash, "risky")
    h.await(first, RunState.SUCCEEDED)

    h.definitions.setUnsafeExecution(hash, "alice", "risky", false, "ops", java.time.Instant.now())

    assertEquals(CreateRunResult.UnsafeNotAllowed("risky"), h.create(hash, "risky"))
    assertEquals("root", h.record(first).unsafeExecution!!.setBy)
  }

  @Test
  fun `a safe pipeline is not affected by the setting and records no unsafe execution`() {
    val h = harness()
    val hash = h.upload("calm")

    val id = h.start(hash, "calm")

    assertNull(h.await(id, RunState.SUCCEEDED).unsafeExecution)
  }

  @Test
  fun `allowing one version does not allow another`() {
    val h = harness()
    val v1 = h.upload("risky", unsafeBody)
    val v2 = h.upload("risky", unsafeBody)
    h.definitions.setUnsafeExecution(v1, "alice", "risky", true, "root", java.time.Instant.now())

    assertIs<CreateRunResult.Accepted>(h.create(v1, "risky"))
    assertEquals(CreateRunResult.UnsafeNotAllowed("risky"), h.create(v2, "risky"))
  }

  // ---- outcomes ----

  @Test
  fun `a pipeline that fails ends failed with the reason`() {
    val h = harness()
    val hash = h.upload("bad", """throw new IllegalStateException("boom");""")

    val run = h.awaitEnd(h.start(hash, "bad"))

    assertEquals(RunState.FAILED, run.state)
    assertEquals("java.lang.IllegalStateException", run.failure?.type)
    assertEquals("boom", run.failure?.message)
    assertTrue(run.failure!!.trace.contains("boom"))
  }

  @Test
  fun `stdout and stderr are kept as the run's log in order with their stream`() {
    val h = harness()
    val hash =
        h.upload(
            "talk",
            """
            System.out.println("one");
            System.err.println("two");
            System.out.println("three");
            """
                .trimIndent(),
        )

    val id = h.start(hash, "talk")
    h.await(id, RunState.SUCCEEDED)

    val log = h.runStore.read(id, 0, 100)
    assertEquals(listOf("one", "two", "three"), log.map { it.line })
    assertEquals(
        listOf(LogStream.STDOUT, LogStream.STDERR, LogStream.STDOUT),
        log.map { it.stream },
    )
    assertEquals(listOf(1L, 2L, 3L), log.map { it.seq })
  }

  @Test
  fun `a run times out and ends as timed out when it reacts to being stopped`() {
    val h = harness(runTimeout = Duration.ofMillis(300))
    val hash = h.upload("slow", WAIT_FOR_STOP)

    val run = h.awaitEnd(h.start(hash, "slow"))

    assertEquals(RunState.TIMED_OUT, run.state)
  }

  @Test
  fun `a run that ignores its timeout keeps its concurrency slot until it really ends`() {
    val h = harness(maxConcurrent = 1, runTimeout = Duration.ofMillis(200))
    val stuck = h.upload("stuck", deafUntilReleased("stuck-started"))
    val next = h.upload("next")
    val stuckRun = h.start(stuck, "stuck")
    h.awaitFile(h.shared("stuck", "stuck-started"))
    val waiting = h.start(next, "next")

    h.await(stuckRun, RunState.TIMED_OUT_UNFINISHED)
    Thread.sleep(300)
    assertEquals(RunState.QUEUED, h.state(waiting), "the unfinished run still holds the slot")
    assertEquals(1, h.scheduler.stats().active)

    h.release("stuck")
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(stuckRun).state, "it finished, late but fine")
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(waiting).state)
  }

  // ---- concurrency ----

  @Test
  fun `no more runs execute at once than the limit and the rest wait their turn in order`() {
    val h = harness(maxConcurrent = 1)
    val long = h.upload("long", holdUntilReleased("long-started"))
    val short = h.upload("short")
    val first = h.start(long, "long")
    h.awaitFile(h.shared("long", "long-started"))
    val second = h.start(short, "short")
    val third = h.start(short, "short")

    Thread.sleep(200)
    assertEquals(RunState.QUEUED, h.state(second))
    assertEquals(RunState.QUEUED, h.state(third))
    assertEquals(SchedulerStats(queued = 2, waiting = 0, active = 1), h.scheduler.stats())

    h.release("long")

    assertEquals(RunState.SUCCEEDED, h.awaitEnd(first).state)
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(second).state)
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(third).state)
    assertTrue(h.record(second).startedAt!! <= h.record(third).startedAt!!)
  }

  // ---- cancelling ----

  @Test
  fun `a run that has not started is cancelled at once and never executes`() {
    val h = harness(maxConcurrent = 1)
    val long = h.upload("long", holdUntilReleased("long-started"))
    val marker =
        h.upload(
            "marker",
            """context.getFiles().writeText(FileScope.PIPELINE_SHARED, "ran", "x");""",
        )
    h.start(long, "long")
    h.awaitFile(h.shared("long", "long-started"))
    val queued = h.start(marker, "marker")

    val result = h.service.cancel(queued, Visibility.All)

    assertEquals(CancelResult.Cancelled, result)
    assertEquals(RunState.CANCELLED, h.state(queued))
    h.release("long")
    Thread.sleep(300)
    assertFalse(Files.exists(h.shared("marker", "ran")))
    assertEquals(RunState.CANCELLED, h.state(queued))
  }

  @Test
  fun `a running run is asked to stop and ends cancelled when it reacts`() {
    val h = harness()
    val hash = h.upload("waits", WAIT_FOR_STOP)
    val id = h.start(hash, "waits")
    h.await(id, RunState.RUNNING)

    assertEquals(CancelResult.CancellationRequested, h.service.cancel(id, Visibility.All))

    assertEquals(RunState.CANCELLED, h.awaitEnd(id).state)
  }

  @Test
  fun `cancelling a finished run says it is finished and changes nothing`() {
    val h = harness()
    val id = h.start(h.upload("quick"), "quick")
    h.await(id, RunState.SUCCEEDED)

    assertEquals(
        CancelResult.AlreadyFinished(RunState.SUCCEEDED),
        h.service.cancel(id, Visibility.All),
    )
    assertEquals(RunState.SUCCEEDED, h.state(id))
  }

  @Test
  fun `cancelling a run the requester may not see, or that does not exist, is not found`() {
    val h = harness()
    val hash = h.upload("bobs", WAIT_FOR_STOP, uploader = "bob")
    val id = h.start(hash, "bobs")
    h.await(id, RunState.RUNNING)

    assertEquals(CancelResult.NotFound, h.service.cancel(id, Visibility.OwnedBy("alice")))
    assertEquals(CancelResult.NotFound, h.service.cancel(UUID.randomUUID(), Visibility.All))
    assertEquals(RunState.RUNNING, h.state(id))
    h.service.cancel(id, Visibility.OwnedBy("bob"))
    assertEquals(RunState.CANCELLED, h.awaitEnd(id).state)
  }

  // ---- the guarantee of an end ----

  @Test
  fun `a run that cannot be started still ends, frees its slot and does not block the next`() {
    val h = harness(maxConcurrent = 1, jarDirectory = java.nio.file.Path.of("/no/such/dir"))
    val hash = h.upload("doomed")

    val first = h.start(hash, "doomed")
    val second = h.start(hash, "doomed")

    val run = h.awaitEnd(first)
    assertEquals(RunState.FAILED, run.state)
    assertNotNull(run.failure)
    assertEquals(RunState.FAILED, h.awaitEnd(second).state)
    assertEquals(SchedulerStats(0, 0, 0), h.scheduler.stats())
  }

  @Test
  fun `the copy of the jar made for a run is removed when the run ends`() {
    val h = harness()
    val id = h.start(h.upload("quick"), "quick")
    h.awaitEnd(id)

    val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
    while (Files.list(h.jars).use { it.count() } > 0 && System.nanoTime() < deadline) {
      Thread.sleep(20)
    }

    assertEquals(0, Files.list(h.jars).use { it.count() })
  }

  @Test
  fun `the copy of the jar made for a run is named for that run`() {
    val h = harness()
    val id = h.start(h.upload("held", holdUntilReleased("held-started")), "held")
    h.awaitFile(h.shared("held", "held-started"))

    val copies = Files.list(h.jars).use { it.toList() }

    assertEquals(listOf(id), copies.map { RunJarFiles.runOf(it) }, "$copies")
    h.release("held")
  }

  // ---- directories ----

  @Test
  fun `the run id names the private directory and a successful run's directory is removed`() {
    val h = harness()
    val hash = h.upload("tidy")

    val id = h.start(hash, "tidy")
    h.await(id, RunState.SUCCEEDED)

    val created =
        h.workspaceEvents.filterIsInstance<WorkspaceEvent.Created>().filter { it.runId != null }
    assertEquals(listOf(id.toString()), created.map { it.runId })
    assertFalse(Files.exists(h.runRoot.resolve("tidy").resolve(id.toString())))
  }

  @Test
  fun `a failed run keeps its private directory for the retention period`() {
    val h = harness()
    val hash = h.upload("kept", """throw new IllegalStateException("no");""")

    val id = h.start(hash, "kept")
    h.awaitEnd(id)

    assertTrue(Files.isDirectory(h.runRoot.resolve("kept").resolve(id.toString())))
  }

  // ---- declared shared resources must exist and be enabled ----

  private fun declaring(vararg names: String) =
      RunHarness.DEFAULT_DECLARATION + ", resources = {" + names.joinToString { "\"$it\"" } + "}"

  @Test
  fun `a pipeline that declares a resource that is not defined is refused and leaves no run`() {
    val h = harness()
    val hash = h.upload("needs", declaration = declaring("ghost"))

    val result = h.create(hash, "needs")

    assertEquals(
        CreateRunResult.ResourcesUnavailable(
            listOf(ResourceProblem("ghost", ResourceProblemKind.UNKNOWN))
        ),
        result,
    )
    assertEquals(0, h.runCount())
  }

  @Test
  fun `a pipeline that declares a disabled resource is refused apart from an unknown one`() {
    val h = harness()
    h.defineResource("fine")
    h.defineResource("off", enabled = false)
    val hash = h.upload("needs", declaration = declaring("fine", "off", "ghost"))

    val result = h.create(hash, "needs")

    assertEquals(
        CreateRunResult.ResourcesUnavailable(
            listOf(
                ResourceProblem("off", ResourceProblemKind.DISABLED),
                ResourceProblem("ghost", ResourceProblemKind.UNKNOWN),
            )
        ),
        result,
    )
    assertEquals(0, h.runCount())
  }

  @Test
  fun `a pipeline whose resources are defined and enabled is accepted`() {
    val h = harness()
    h.defineResource("a")
    h.defineResource("b", capacity = 2)
    val hash = h.upload("needs", declaration = declaring("a", "b"))

    assertIs<CreateRunResult.Accepted>(h.create(hash, "needs"))
  }

  @Test
  fun `a resource that is defined later makes the same pipeline runnable`() {
    val h = harness()
    val hash = h.upload("needs", declaration = declaring("late"))
    assertIs<CreateRunResult.ResourcesUnavailable>(h.create(hash, "needs"))

    h.defineResource("late")

    assertIs<CreateRunResult.Accepted>(h.create(hash, "needs"))
  }

  @Test
  fun `a request that is refused for another reason first is not reported as a resource problem`() {
    val h = harness()
    val hash =
        h.upload(
            "needs",
            declaration = declaring("ghost") + ", parameters = {@Param(name = \"p\")}",
        )

    assertIs<CreateRunResult.InvalidParameters>(h.create(hash, "needs"))
    assertEquals(CreateRunResult.DefinitionNotFound, h.create(hash, "other"))
  }

  // ---- shared resources join here later ----

  /** A gate that lets one run at a time hold each named resource. */
  private class ExclusiveGate : ResourceGate {
    private val holders = java.util.concurrent.ConcurrentHashMap<String, UUID>()
    val released = java.util.concurrent.CopyOnWriteArrayList<UUID>()

    override fun tryAcquire(run: PendingRun): GateDecision {
      if (run.resources.any { holders[it]?.let { h -> h != run.id } == true }) {
        return GateDecision.WAIT
      }
      run.resources.forEach { holders[it] = run.id }
      return GateDecision.GRANTED
    }

    override fun release(runId: UUID) {
      released += runId
      holders.entries.removeIf { it.value == runId }
    }
  }

  private val withDb = RunHarness.DEFAULT_DECLARATION + ", resources = {\"db\"}"

  @Test
  fun `a run waiting for a resource holds no slot, so a run behind it may start first`() {
    val gate = ExclusiveGate()
    val h = harness(maxConcurrent = 3, gate = gate)
    h.defineResource("db")
    val holder = h.upload("holder", holdUntilReleased("holder-started"), declaration = withDb)
    val needsDb = h.upload("needs-db", "", declaration = withDb)
    val tail = h.upload("tail")
    val first = h.start(holder, "holder")
    h.awaitFile(h.shared("holder", "holder-started"))
    val waiting = h.start(needsDb, "needs-db")
    h.await(waiting, RunState.WAITING_FOR_RESOURCES)
    val other = h.upload("other", holdUntilReleased("other-started"))
    h.start(other, "other")
    h.awaitFile(h.shared("other", "other-started"))
    assertEquals(SchedulerStats(queued = 0, waiting = 1, active = 2), h.scheduler.stats())

    val behind = h.start(tail, "tail")

    assertEquals(RunState.SUCCEEDED, h.awaitEnd(behind).state)
    assertEquals(RunState.WAITING_FOR_RESOURCES, h.state(waiting))
    h.release("holder")
    h.await(first, RunState.SUCCEEDED)
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(waiting).state)
    h.release("other")
  }

  @Test
  fun `a run waiting for a resource can be cancelled and gives up what it waited for`() {
    val gate = ExclusiveGate()
    val h = harness(maxConcurrent = 2, gate = gate)
    h.defineResource("db")
    val holder = h.upload("holder", holdUntilReleased("holder-started"), declaration = withDb)
    val needsDb = h.upload("needs-db", "", declaration = withDb)
    h.start(holder, "holder")
    h.awaitFile(h.shared("holder", "holder-started"))
    val waiting = h.start(needsDb, "needs-db")
    h.await(waiting, RunState.WAITING_FOR_RESOURCES)

    assertEquals(CancelResult.Cancelled, h.service.cancel(waiting, Visibility.All))

    assertEquals(RunState.CANCELLED, h.state(waiting))
    assertTrue(waiting in gate.released)
  }

  @Test
  fun `a gate that frees a resource tells the scheduler to look again`() {
    val gate = ExclusiveGate()
    val h = harness(maxConcurrent = 2, gate = gate)
    h.defineResource("db")
    val holder = h.upload("holder", holdUntilReleased("holder-started"), declaration = withDb)
    val needsDb = h.upload("needs-db", "", declaration = withDb)
    val first = h.start(holder, "holder")
    h.awaitFile(h.shared("holder", "holder-started"))
    val waiting = h.start(needsDb, "needs-db")
    h.await(waiting, RunState.WAITING_FOR_RESOURCES)

    gate.release(first) // freed by an administrator, not by the run ending
    h.scheduler.wake()

    assertEquals(RunState.SUCCEEDED, h.awaitEnd(waiting).state)
    h.release("holder")
  }

  // ---- shutdown ----

  @Test
  fun `a shutdown interrupts what is running and what is queued`() {
    val h = harness(maxConcurrent = 1)
    val long = h.upload("long", WAIT_FOR_STOP)
    val running = h.start(long, "long")
    h.await(running, RunState.RUNNING)
    val queued = h.start(long, "long")

    h.scheduler.close()

    assertEquals(RunState.INTERRUPTED, h.state(running))
    assertEquals(RunState.INTERRUPTED, h.state(queued))
  }

  @Test
  fun `a run that does not stop within the grace time of a shutdown leaves no copy of its jar`() {
    val h = harness(shutdownGrace = Duration.ofMillis(500))
    val deaf = h.start(h.upload("deaf", deafUntilReleased("deaf-started")), "deaf")
    h.awaitFile(h.shared("deaf", "deaf-started"))

    h.scheduler.close()

    assertEquals(RunState.INTERRUPTED, h.state(deaf))
    assertEquals(emptyList(), Files.list(h.jars).use { it.toList() })
    h.release("deaf")
  }
}
