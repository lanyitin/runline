package dev.lawlan.runline.engine.run

import dev.lawlan.runline.engine.support.RunHarness
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

/** How the scheduler treats the answers of a [ResourceGate], whatever implements it. */
class RunSchedulerGateTest {
  private val harnesses = mutableListOf<RunHarness>()

  @AfterTest fun closeAll() = harnesses.forEach { it.close() }

  private fun harness(gate: ResourceGate) =
      RunHarness(maxConcurrent = 2, gate = gate).also { harnesses += it }

  /** A gate that gives the answer it is told to and remembers what it was asked. */
  private class ScriptedGate(@Volatile var answer: (PendingRun) -> GateDecision) : ResourceGate {
    val released = CopyOnWriteArrayList<UUID>()
    val asked = CopyOnWriteArrayList<UUID>()
    val wake = AtomicReference<() -> Unit>()

    override fun tryAcquire(run: PendingRun): GateDecision {
      asked += run.id
      return answer(run)
    }

    override fun release(runId: UUID) {
      released += runId
    }

    override fun attach(wake: () -> Unit) {
      this.wake.set(wake)
    }
  }

  private val refusal = FailureInfo("ResourceUnavailable", "資源 db 已停用", "")

  @Test
  fun `a run the gate refuses ends failed with the gate's reason and never starts`() {
    val gate = ScriptedGate { GateDecision.Refused(refusal) }
    val h = harness(gate)
    val hash =
        h.upload("p", "context.getFiles().writeText(FileScope.PIPELINE_SHARED, \"ran\", \"x\");")

    val id = h.start(hash, "p")

    val ended = h.awaitEnd(id)
    assertEquals(RunState.FAILED, ended.state)
    assertEquals(refusal, ended.failure)
    assertNull(ended.startedAt)
    assertFalse(java.nio.file.Files.exists(h.shared("p", "ran")))
    assertTrue(id in gate.released)
  }

  @Test
  fun `a refused run does not hold up the runs behind it`() {
    val gate = ScriptedGate {
      if (it.pipelineName == "bad") GateDecision.Refused(refusal) else GateDecision.GRANTED
    }
    val h = harness(gate)
    val bad = h.upload("bad")
    val good = h.upload("good")
    val refused = h.start(bad, "bad")
    val fine = h.start(good, "good")

    assertEquals(RunState.FAILED, h.awaitEnd(refused).state)
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(fine).state)
  }

  @Test
  fun `the scheduler gives the gate a way to wake it`() {
    val gate = ScriptedGate { GateDecision.WAIT }
    val h = harness(gate)
    val hash = h.upload("p")
    val id = h.start(hash, "p")
    h.await(id, RunState.WAITING_FOR_RESOURCES)

    gate.answer = { GateDecision.GRANTED }
    gate.wake.get()()

    assertEquals(RunState.SUCCEEDED, h.awaitEnd(id).state)
  }
}
