package dev.lawlan.runline.engine.allowlist

import dev.lawlan.runline.analyzer.AllowListEntry
import dev.lawlan.runline.analyzer.Verdict
import dev.lawlan.runline.engine.run.NewRun
import dev.lawlan.runline.engine.run.PostgresRunStore
import dev.lawlan.runline.engine.run.RunSource
import dev.lawlan.runline.engine.support.AllowListRig
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** What a change of the allow list, while it is judging again, does not hold up (WI-10). */
class AllowListChangeConcurrencyTest {
  private val rig = AllowListRig(listOf(AllowListEntry("java.lang")))

  @Test
  fun `reading definitions and creating a run go on while a change is rejudging`() {
    val hash = rig.uploadNeedingUtil("busy")
    val id = rig.definitions.find(hash, "alice", "busy")!!.id
    val runs = PostgresRunStore(rig.dataSource)
    val inside = CountDownLatch(1)
    val finish = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(2)
    try {
      val change = pool.submit {
        rig.store.change { session ->
          // The definition's row is changed and locked by this transaction until it ends.
          session.rejudge(
              id,
              NewJudgement(Verdict.SAFE, emptyList(), "2", false, "root", rig.clock.instant()),
          )
          inside.countDown()
          finish.await(30, TimeUnit.SECONDS)
        }
      }
      assertTrue(inside.await(10, TimeUnit.SECONDS))

      val others =
          pool.submit<Pair<Verdict, UUID>> {
            val seen = rig.definitions.find(hash, "alice", "busy")!!.verdict
            val run =
                runs.insert(
                    NewRun(
                        UUID.randomUUID(),
                        id,
                        RunSource.Manual("alice"),
                        emptyMap(),
                        rig.clock.instant(),
                        null,
                    )
                )
            seen to run.id
          }

      val (seen, _) = others.get(10, TimeUnit.SECONDS)
      assertEquals(Verdict.UNSAFE, seen, "what was committed is read, not the change in progress")
      finish.countDown()
      change.get(30, TimeUnit.SECONDS)
      assertEquals(Verdict.SAFE, rig.definitions.find(hash, "alice", "busy")!!.verdict)
    } finally {
      finish.countDown()
      pool.shutdownNow()
    }
  }
}
