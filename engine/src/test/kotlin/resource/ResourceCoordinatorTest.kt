package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.engine.auth.ApiIdentity
import dev.lawlan.runline.engine.auth.Role
import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.run.GateDecision
import dev.lawlan.runline.engine.run.PendingRun
import dev.lawlan.runline.engine.support.MutableClock
import dev.lawlan.runline.engine.support.migratedDatabase
import io.opentelemetry.api.OpenTelemetry
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ResourceCoordinatorTest {
  private val store = PostgresResourceStore(dataSourceOf(migratedDatabase()))
  private val admin = ResourceAdmin(store, MutableClock()) {}
  private val root = ApiIdentity("root", Role.ADMIN)
  private val clock = MutableClock()
  private val wakes = AtomicInteger()
  private val coordinators = mutableListOf<ResourceCoordinator>()

  private fun coordinator(timeout: Duration = Duration.ofHours(1)) =
      ResourceCoordinator(
              ResourceAvailability(store),
              clock,
              timeout,
              ResourceTelemetry(OpenTelemetry.noop()),
          )
          .also {
            it.attach { wakes.incrementAndGet() }
            coordinators += it
          }

  @AfterTest fun closeAll() = coordinators.forEach { it.close() }

  private fun define(name: String, capacity: Int = 1) {
    assertIs<CreateResourceResult.Created>(admin.create(name, capacity, root))
  }

  private fun run(vararg resources: String, pipeline: String = "p") =
      PendingRun(UUID.randomUUID(), pipeline, resources.toList())

  private fun ResourceCoordinator.granted(run: PendingRun) =
      assertEquals(GateDecision.GRANTED, tryAcquire(run), "run ${run.id} should be granted")

  private fun ResourceCoordinator.waits(run: PendingRun) =
      assertEquals(GateDecision.WAIT, tryAcquire(run), "run ${run.id} should wait")

  // ---- capacity ----

  @Test
  fun `a run that declares no resources is granted at once and leaves no trace`() {
    val c = coordinator()
    val r = run()

    c.granted(r)
    c.release(r.id)

    assertEquals(0, wakes.get())
  }

  @Test
  fun `a resource of capacity one is held by one run at a time`() {
    define("lemonade", 1)
    val c = coordinator()
    val first = run("lemonade")
    val second = run("lemonade")

    c.granted(first)
    c.waits(second)
    c.release(first.id)

    c.granted(second)
  }

  @Test
  fun `a resource of capacity n is held by at most n runs at once`() {
    define("gpu", 2)
    val c = coordinator()
    val a = run("gpu")
    val b = run("gpu")
    val third = run("gpu")

    c.granted(a)
    c.granted(b)
    c.waits(third)
    c.release(b.id)

    c.granted(third)
  }

  @Test
  fun `runs on different resources do not affect each other`() {
    define("a")
    define("b")
    val c = coordinator()

    c.granted(run("a"))
    c.granted(run("b"))
  }

  // ---- all or nothing ----

  @Test
  fun `a run gets all its resources together or none of them`() {
    define("a")
    define("b")
    val c = coordinator()
    val holdsB = run("b")
    c.granted(holdsB)
    val needsBoth = run("a", "b")

    c.waits(needsBoth)

    // It did not take "a" while waiting for "b": someone else can have it, and a later run that
    // needs "a" alone gets it without waiting behind a run that holds nothing.
    assertEquals(emptyList(), c.activity("a").holders)
    c.release(holdsB.id)
    c.granted(needsBoth)
    assertEquals(listOf(needsBoth.id), c.activity("a").holders.map { it.runId })
    assertEquals(listOf(needsBoth.id), c.activity("b").holders.map { it.runId })
  }

  @Test
  fun `a resource declared twice is taken once`() {
    define("a", 1)
    val c = coordinator()
    val r = run("a", "a")

    c.granted(r)

    assertEquals(1, c.activity("a").holders.size)
    c.release(r.id)
    c.granted(run("a"))
  }

  // ---- first in, first out ----

  @Test
  fun `waiting runs are served in the order they began to wait`() {
    define("a")
    val c = coordinator()
    val holder = run("a")
    val w1 = run("a")
    val w2 = run("a")
    val w3 = run("a")
    c.granted(holder)
    c.waits(w1)
    c.waits(w2)
    c.waits(w3)

    c.release(holder.id)

    c.waits(w3) // w1 and w2 are ahead
    c.waits(w2) // w1 is ahead
    c.granted(w1)
    c.release(w1.id)
    c.granted(w2)
  }

  @Test
  fun `a newcomer does not overtake an earlier waiter even when the resource is free`() {
    define("a")
    define("b")
    val c = coordinator()
    val holdsB = run("b")
    c.granted(holdsB)
    val needsBoth = run("a", "b")
    c.waits(needsBoth) // "a" is free but the run waits for "b"
    val needsA = run("a")

    c.waits(needsA)

    c.release(holdsB.id)
    c.granted(needsBoth)
    c.release(needsBoth.id)
    c.granted(needsA)
  }

  // ---- release ----

  @Test
  fun `releasing twice gives back only once`() {
    define("gpu", 2)
    val c = coordinator()
    val a = run("gpu")
    val b = run("gpu")
    c.granted(a)
    c.granted(b)

    c.release(a.id)
    c.release(a.id)

    c.granted(run("gpu"))
    c.waits(run("gpu")) // b still holds one
  }

  @Test
  fun `releasing a run that holds nothing is harmless and wakes nobody`() {
    val c = coordinator()

    c.release(UUID.randomUUID())

    assertEquals(0, wakes.get())
  }

  @Test
  fun `freeing a resource wakes the scheduler`() {
    define("a")
    val c = coordinator()
    val holder = run("a")
    c.granted(holder)

    c.release(holder.id)

    assertEquals(1, wakes.get())
  }

  @Test
  fun `a waiting run that is released leaves the queue and does not get the resource`() {
    define("a")
    val c = coordinator()
    val holder = run("a")
    val cancelled = run("a")
    val next = run("a")
    c.granted(holder)
    c.waits(cancelled)
    c.waits(next)

    c.release(cancelled.id) // cancelled while waiting
    c.release(holder.id)

    c.granted(next)
    assertEquals(listOf(next.id), c.activity("a").holders.map { it.runId })
    assertEquals(emptyList(), c.activity("a").waiters)
  }

  // ---- wait timeout ----

  @Test
  fun `a run that waits too long is refused as a wait timeout and leaves the queue`() {
    define("a")
    val c = coordinator(timeout = Duration.ofMillis(200))
    val holder = run("a")
    val late = run("a")
    val behind = run("a")
    c.granted(holder)
    c.waits(late)
    c.waits(behind)

    awaitWakes(1)
    val decision = c.tryAcquire(late)

    val refused = assertIs<GateDecision.Refused>(decision).failure
    assertEquals(ResourceFailures.WAIT_TIMEOUT, refused.type)
    assertTrue(refused.message!!.contains("等待資源逾時"), refused.message)
    assertTrue(refused.message!!.contains("a"), refused.message)
    assertEquals(listOf(behind.id), c.activity("a").waiters.map { it.runId })
    c.release(holder.id)
    c.granted(behind)
  }

  @Test
  fun `a waiting run is told when its wait has run out so the scheduler looks again`() {
    define("a")
    val c = coordinator(timeout = Duration.ofMillis(100))
    c.granted(run("a"))
    c.waits(run("a"))

    awaitWakes(1)
  }

  @Test
  fun `a run whose resource became free just as its wait ran out is granted, not failed`() {
    define("a")
    val c = coordinator(timeout = Duration.ofMillis(100))
    val holder = run("a")
    val waiter = run("a")
    c.granted(holder)
    c.waits(waiter)
    awaitWakes(1)
    c.release(holder.id)

    c.granted(waiter)
  }

  @Test
  fun `a holder is never timed out, only waiters`() {
    define("a")
    val c = coordinator(timeout = Duration.ofMillis(50))
    val holder = run("a")
    c.granted(holder)

    Thread.sleep(200)

    assertEquals(listOf(holder.id), c.activity("a").holders.map { it.runId })
    c.waits(run("a"))
  }

  private fun awaitWakes(count: Int) {
    val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
    while (wakes.get() < count) {
      check(System.nanoTime() < deadline) { "the scheduler was not woken" }
      Thread.sleep(10)
    }
  }

  // ---- unavailable resources ----

  @Test
  fun `a run whose resource was disabled meanwhile is refused as unavailable`() {
    define("a")
    val c = coordinator()
    val holder = run("a")
    val waiter = run("a")
    c.granted(holder)
    c.waits(waiter)

    admin.update("a", null, false, root)

    val failure = assertIs<GateDecision.Refused>(c.tryAcquire(waiter)).failure
    assertEquals(ResourceFailures.UNAVAILABLE, failure.type)
    assertTrue(failure.message!!.contains("a"), failure.message)
    assertTrue(failure.message!!.contains("停用"), failure.message)
    assertEquals(emptyList(), c.activity("a").waiters)
  }

  @Test
  fun `a run that declares a resource that is not defined is refused as unavailable`() {
    val c = coordinator()

    val failure = assertIs<GateDecision.Refused>(c.tryAcquire(run("ghost"))).failure

    assertEquals(ResourceFailures.UNAVAILABLE, failure.type)
    assertTrue(failure.message!!.contains("ghost"), failure.message)
    assertTrue(failure.message!!.contains("未定義"), failure.message)
  }

  @Test
  fun `a run that expects another type than the resource has is refused as unavailable and holds nothing`() {
    define("a")
    define("b")
    val c = coordinator()
    val mismatched =
        PendingRun(UUID.randomUUID(), "p", listOf("a", "b"), mapOf("a" to "file", "b" to "counter"))

    val failure = assertIs<GateDecision.Refused>(c.tryAcquire(mismatched)).failure

    assertEquals(ResourceFailures.UNAVAILABLE, failure.type)
    assertTrue(failure.message!!.contains("a"), failure.message)
    assertTrue(failure.message!!.contains("型別不符"), failure.message)
    assertEquals(emptyList(), c.activity("a").holders)
    assertEquals(emptyList(), c.activity("b").holders)
  }

  @Test
  fun `a run that expects the type the resource has is granted`() {
    define("a")
    val c = coordinator()

    c.granted(PendingRun(UUID.randomUUID(), "p", listOf("a"), mapOf("a" to "counter")))
  }

  @Test
  fun `a refusal grants nothing, not even the resources that are fine`() {
    define("a")
    val c = coordinator()

    assertIs<GateDecision.Refused>(c.tryAcquire(run("a", "ghost")))

    assertEquals(emptyList(), c.activity("a").holders)
  }

  // ---- capacity changes ----

  @Test
  fun `lowering the capacity below the holders keeps them but admits nobody new`() {
    define("gpu", 2)
    val c = coordinator()
    val a = run("gpu")
    val b = run("gpu")
    c.granted(a)
    c.granted(b)

    admin.update("gpu", 1, null, root)

    c.waits(run("gpu"))
    c.release(a.id)
    c.waits(run("gpu")) // b still holds the only slot
    assertEquals(listOf(b.id), c.activity("gpu").holders.map { it.runId })
  }

  @Test
  fun `raising the capacity admits waiting runs on the next look`() {
    define("gpu", 1)
    val c = coordinator()
    c.granted(run("gpu"))
    val waiter = run("gpu")
    c.waits(waiter)

    admin.update("gpu", 2, null, root)

    c.granted(waiter)
  }

  // ---- what an administrator sees ----

  @Test
  fun `holders and waiters are listed with the run, its pipeline and since when`() {
    define("a")
    define("b")
    val c = coordinator()
    val holder = run("a", pipeline = "holder")
    c.granted(holder)
    val t0 = clock.instant()
    clock.advance(Duration.ofSeconds(30))
    val w1 = run("a", "b", pipeline = "first")
    c.waits(w1)
    clock.advance(Duration.ofSeconds(5))
    val w2 = run("a", pipeline = "second")
    c.waits(w2)

    val activity = c.activity("a")

    assertEquals(listOf(Holder(holder.id, "holder", t0)), activity.holders)
    assertEquals(
        listOf(
            Waiter(w1.id, "first", listOf("a", "b"), t0.plusSeconds(30)),
            Waiter(w2.id, "second", listOf("a"), t0.plusSeconds(35)),
        ),
        activity.waiters,
    )
    assertEquals(listOf(w1.id), c.activity("b").waiters.map { it.runId })
    assertEquals(ResourceActivity(emptyList(), emptyList()), c.activity("unused"))
  }

  // ---- forced release ----

  @Test
  fun `an administrator can force a holder to let go, and the next waiter proceeds`() {
    define("a")
    val c = coordinator()
    val stuck = run("a", pipeline = "stuck")
    val waiter = run("a")
    c.granted(stuck)
    c.waits(waiter)
    val before = wakes.get()

    val result = c.forceRelease("a", stuck.id, root)

    assertEquals(
        ForceReleaseResult.Released(Holder(stuck.id, "stuck", clock.instant())),
        result,
    )
    assertEquals(before + 1, wakes.get())
    c.granted(waiter)
  }

  @Test
  fun `forcing a release frees only the named resource of that run`() {
    define("a")
    define("b")
    val c = coordinator()
    val both = run("a", "b")
    c.granted(both)

    c.forceRelease("a", both.id, root)

    assertEquals(emptyList(), c.activity("a").holders)
    assertEquals(listOf(both.id), c.activity("b").holders.map { it.runId })
  }

  @Test
  fun `forcing a release of a run that does not hold the resource says so`() {
    define("a")
    val c = coordinator()
    val waiter = run("a")
    c.granted(run("a"))
    c.waits(waiter)

    assertEquals(ForceReleaseResult.NotHeld, c.forceRelease("a", waiter.id, root))
    assertEquals(ForceReleaseResult.NotHeld, c.forceRelease("a", UUID.randomUUID(), root))
    assertEquals(ForceReleaseResult.NotHeld, c.forceRelease("unknown", waiter.id, root))
  }

  @Test
  fun `a run that was forced to let go and later ends does not free what the next holder has`() {
    define("a")
    val c = coordinator()
    val stuck = run("a")
    c.granted(stuck)
    c.forceRelease("a", stuck.id, root)
    val next = run("a")
    c.granted(next)

    c.release(stuck.id) // the stuck run finally ends

    c.waits(run("a"))
    assertEquals(listOf(next.id), c.activity("a").holders.map { it.runId })
  }

  // ---- exclusion under concurrency ----

  @Test
  fun `many threads competing never exceed the capacity`() {
    define("a", 3)
    val c = coordinator()
    val inside = AtomicInteger()
    val peak = AtomicInteger()
    val done = AtomicInteger()
    val threads =
        (1..12).map {
          Thread.ofPlatform().start {
            repeat(40) {
              val r = run("a")
              while (c.tryAcquire(r) != GateDecision.GRANTED) Thread.onSpinWait()
              val now = inside.incrementAndGet()
              peak.accumulateAndGet(now, ::maxOf)
              Thread.yield()
              inside.decrementAndGet()
              c.release(r.id)
              done.incrementAndGet()
            }
          }
        }

    threads.forEach { it.join(60_000) }

    assertEquals(480, done.get())
    assertTrue(peak.get() in 1..3, "peak was ${peak.get()}")
    assertEquals(emptyList(), c.activity("a").holders)
  }

  // ---- removal (WI-40) ----

  private fun ResourceCoordinator.removed(name: String) =
      removeWhenUnused(name) { store.delete(name) }

  @Test
  fun `a resource nobody holds or waits for is removed`() {
    define("a")
    define("b")
    val c = coordinator()

    assertEquals(RemovalOutcome.Removed, c.removed("a"))

    assertNull(store.find("a"))
    assertNotNull(store.find("b"))
  }

  @Test
  fun `removing a resource that does not exist says so`() {
    assertEquals(RemovalOutcome.NotFound, coordinator().removed("ghost"))
  }

  @Test
  fun `a resource that a run holds is not removed and nothing changes`() {
    define("a")
    val c = coordinator()
    val holder = run("a")
    c.granted(holder)

    assertEquals(RemovalOutcome.InUse(holders = 1, waiters = 0), c.removed("a"))

    assertNotNull(store.find("a"))
    assertEquals(listOf(holder.id), c.activity("a").holders.map { it.runId })
  }

  @Test
  fun `a resource a run waits for is not removed, even when it is free`() {
    define("a")
    define("b")
    val c = coordinator()
    c.granted(run("b"))
    val waiter = run("a", "b")
    c.waits(waiter)

    assertEquals(RemovalOutcome.InUse(holders = 0, waiters = 1), c.removed("a"))

    assertNotNull(store.find("a"))
    assertEquals(listOf(waiter.id), c.activity("a").waiters.map { it.runId })
  }

  @Test
  fun `a resource can be removed once its holder has released it`() {
    define("a")
    val c = coordinator()
    val holder = run("a")
    c.granted(holder)
    c.release(holder.id)

    assertEquals(RemovalOutcome.Removed, c.removed("a"))
  }

  @Test
  fun `after removal a run that declares the resource is refused as undefined`() {
    define("a")
    val c = coordinator()
    c.removed("a")

    val failure = assertIs<GateDecision.Refused>(c.tryAcquire(run("a"))).failure

    assertEquals(ResourceFailures.UNAVAILABLE, failure.type)
    assertTrue(failure.message!!.contains("未定義"), failure.message)
  }

  @Test
  fun `a removed name can be defined again as another type, and a run declaring the old type is refused`() {
    define("a")
    val c = coordinator()
    c.removed("a")
    store.insert(
        SharedResource(
            "a",
            1,
            true,
            "root",
            java.time.Instant.now(),
            "root",
            java.time.Instant.now(),
            ResourceType.FILE,
        )
    )

    val failure =
        assertIs<GateDecision.Refused>(
                c.tryAcquire(
                    PendingRun(UUID.randomUUID(), "p", listOf("a"), mapOf("a" to "counter"))
                )
            )
            .failure

    assertTrue(failure.message!!.contains("型別不符"), failure.message)
    c.granted(PendingRun(UUID.randomUUID(), "p", listOf("a"), mapOf("a" to "file")))
  }

  /**
   * The removal and the acquisition race for the same resource, many times with both started at the
   * same moment. However they interleave, the result is consistent: a removed resource has no
   * holder and is not granted, and a granted one is not removed.
   */
  @Test
  fun `removal and acquisition racing for one resource always end consistently`() {
    val c = coordinator()
    val pool = Executors.newFixedThreadPool(2)
    try {
      repeat(RACES) { round ->
        val name = "race-$round"
        define(name)
        val contender = run(name)
        val barrier = CyclicBarrier(2)
        val acquiring =
            pool.submit<GateDecision> {
              barrier.await()
              c.tryAcquire(contender)
            }
        val removing =
            pool.submit<RemovalOutcome> {
              barrier.await()
              c.removed(name)
            }

        val decision = acquiring.get()
        val outcome = removing.get()

        when (outcome) {
          RemovalOutcome.Removed -> {
            assertIs<GateDecision.Refused>(decision, "removed, yet the run was $decision ($name)")
            assertNull(store.find(name))
            assertEquals(emptyList(), c.activity(name).holders, name)
          }
          is RemovalOutcome.InUse -> {
            assertEquals(GateDecision.GRANTED, decision, name)
            assertNotNull(store.find(name))
            assertEquals(listOf(contender.id), c.activity(name).holders.map { it.runId })
          }
          RemovalOutcome.NotFound -> fail("the resource $name existed")
        }
        c.release(contender.id)
      }
    } finally {
      pool.shutdownNow()
    }
  }

  private companion object {
    const val RACES = 300
  }
}
