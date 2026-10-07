package dev.lawlan.runline.engine.retention

import dev.lawlan.runline.analyzer.AllowListEntry
import dev.lawlan.runline.engine.artifact.Visibility
import dev.lawlan.runline.engine.config.RetentionSettings
import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.run.RunCatalog
import dev.lawlan.runline.engine.run.RunLogFollower
import dev.lawlan.runline.engine.run.RunState
import dev.lawlan.runline.engine.support.AllowListRig
import dev.lawlan.runline.engine.support.MutableClock
import dev.lawlan.runline.engine.support.RetentionData
import dev.lawlan.runline.engine.support.TestTimeouts
import dev.lawlan.runline.engine.support.migratedDatabase
import io.opentelemetry.api.OpenTelemetry
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The clean-up and everything else that goes on at the same time, with real threads on a real
 * PostgreSQL. Each test has its own database, clock and thread pool and shares nothing mutable with
 * another test; what the threads share is only what the test hands them.
 */
class RetentionConcurrencyTest {
  private val dataSource = dataSourceOf(migratedDatabase())
  private val clock = MutableClock()
  private val data = RetentionData(dataSource, clock.instant())
  private val catalog = RunCatalog(data.runs, data.runs)
  private val pool = Executors.newCachedThreadPool()

  private fun cleaner(dataSource: javax.sql.DataSource = this.dataSource, batchSize: Int = 1000) =
      RetentionCleaner(
          PostgresRetentionStore(dataSource),
          RetentionSettings(
              Duration.ofDays(30),
              Duration.ofDays(30),
              Duration.ofDays(7),
              Duration.ofDays(30),
              Duration.ofHours(1),
              batchSize,
          ),
          clock,
          RetentionTelemetry(OpenTelemetry.noop()),
      )

  private fun ago(days: Long) = clock.instant() - Duration.ofDays(days)

  @AfterTest fun stop() = pool.shutdownNow().let {}

  // ---- requests under way ----

  @Test
  fun `a log stream under way finishes cleanly when its run expires, and the run is gone after`() {
    val id = data.run(RunState.SUCCEEDED, ago(40), logLines = 1200)
    val follower = RunLogFollower(catalog, Duration.ofMillis(10))
    val atBatchEnd = CompletableFuture<Unit>()
    val resume = CompletableFuture<Unit>()
    val delivered = mutableListOf<Long>()

    val streaming =
        pool.submit<Boolean> {
          runBlocking {
            follower.follow(id, Visibility.All, 0) { entry ->
              delivered += entry.seq
              // The follower reads the log in batches of 500; stop after the first one.
              if (entry.seq == 500L) {
                atBatchEnd.complete(Unit)
                resume.get(30, TimeUnit.SECONDS)
              }
            }
          }
        }
    atBatchEnd.get(30, TimeUnit.SECONDS)

    val report = cleaner().clean()
    resume.complete(Unit)

    assertEquals(1, report.runs, "the run expired while it was being streamed")
    assertTrue(streaming.get(30, TimeUnit.SECONDS), "the stream ends normally, with no error")
    assertEquals((1L..500L).toList(), delivered, "what it delivered is an unbroken start")
    assertNull(catalog.find(id, Visibility.All), "afterwards the run is not there")
    assertFalse(
        runBlocking {
          withTimeout(TestTimeouts.task.toMillis()) {
            follower.follow(id, Visibility.All, 0) { fail("nothing to deliver") }
          }
        },
        "and a new stream is told it does not exist",
    )
  }

  @Test
  fun `a query that began before the clean-up gets the whole answer`() {
    val id = data.run(RunState.SUCCEEDED, ago(40), logLines = 50)
    // The one statement of a read sees the rows as they were when it began: hold a read open in a
    // transaction that began before the removal and read after it.
    val connection = dataSource.connection
    try {
      connection.autoCommit = false
      connection.transactionIsolation = java.sql.Connection.TRANSACTION_REPEATABLE_READ
      connection.createStatement().use {
        it.executeQuery("SELECT 1").close()
      } // begins the snapshot

      val report = cleaner().clean()

      assertEquals(1, report.runs)
      val lines =
          connection.prepareStatement("SELECT count(*) FROM run_log_entry WHERE run_id = ?").use {
            it.setObject(1, id)
            it.executeQuery().use { rs ->
              rs.next()
              rs.getLong(1)
            }
          }
      assertEquals(50, lines, "the reader that began first still sees its log")
    } finally {
      connection.close()
    }
    assertNull(catalog.find(id, Visibility.All), "a reader that begins after sees nothing")
  }

  @Test
  fun `readers and the clean-up at the same time see a run either whole or not there`() {
    val ids = List(8) { data.run(RunState.SUCCEEDED, ago(40), logLines = 30) }
    val start = CountDownLatch(1)

    val readers = ids.map { id ->
      pool.submit<String?> {
        start.await()
        var gone = false
        var problem: String? = null
        // Read until the run is gone; a run that is there is whole, one that is not stays gone.
        while (!gone && problem == null) {
          val run = catalog.find(id, Visibility.All)
          val log = catalog.log(id, Visibility.All, 0, 500)
          when {
            run == null -> gone = true
            log == null -> gone = true
            log.map { it.seq } != log.map { it.seq }.sorted() -> problem = "$id: out of order"
            log.size > 30 -> problem = "$id: more lines than were written"
          }
        }
        if (problem == null) {
          if (catalog.find(id, Visibility.All) != null) problem = "$id: came back"
        }
        problem
      }
    }
    // Small batches, so the removal takes many statements and readers meet it half way.
    val cleaning = pool.submit {
      start.await()
      val cleaner = cleaner(batchSize = 3)
      while (data.count("run") > 0) cleaner.clean()
    }
    start.countDown()

    cleaning.get(60, TimeUnit.SECONDS)
    assertEquals(emptyList(), readers.mapNotNull { it.get(60, TimeUnit.SECONDS) })
    assertEquals(0, data.count("run_log_entry"))
  }

  // ---- other work on the same database ----

  private val rig = AllowListRig(listOf(AllowListEntry("java.lang")))

  private fun rigRuns(hash: String, pipeline: String, count: Int): List<UUID> {
    val rigData = RetentionData(rig.dataSource, clock.instant())
    val definition = rig.definitions.find(hash, "alice", pipeline)!!.id
    return List(count) { rigData.run(RunState.SUCCEEDED, ago(40), 2, definition) }
  }

  @Test
  fun `the clean-up is not held up by an allow list change that holds its lock and a definition`() {
    val hash = rig.uploadNeedingUtil("busy")
    val expired = rigRuns(hash, "busy", 5)
    val id = rig.definitions.find(hash, "alice", "busy")!!.id
    val inside = CountDownLatch(1)
    val finish = CountDownLatch(1)
    val change = pool.submit {
      rig.store.change { session ->
        // Exclusive lock of the allow list, and the definition's row changed and locked.
        session.rejudge(
            id,
            dev.lawlan.runline.engine.allowlist.NewJudgement(
                dev.lawlan.runline.analyzer.Verdict.SAFE,
                emptyList(),
                "2",
                false,
                "root",
                rig.clock.instant(),
            ),
        )
        inside.countDown()
        finish.await(60, TimeUnit.SECONDS)
      }
    }
    try {
      assertTrue(inside.await(30, TimeUnit.SECONDS))

      val report =
          pool.submit<RetentionReport> { cleaner(rig.dataSource).clean() }.get(30, TimeUnit.SECONDS)

      assertTrue(report.succeeded)
      assertEquals(5, report.runs, "the runs of the definition being rejudged are removed")
      assertEquals(
          emptyList(),
          expired.filter { RetentionData(rig.dataSource, clock.instant()).exists(it) },
      )
    } finally {
      finish.countDown()
    }
    change.get(60, TimeUnit.SECONDS)
  }

  @Test
  fun `an allow list change and an upload are not held up by a clean-up in the middle of its work`() {
    val hash = rig.uploadNeedingUtil("busy")
    rigRuns(hash, "busy", 5)
    // A clean-up statement that has done its deletes and not yet committed holds, at most, locks on
    // the rows it removed. This is exactly that state, held open.
    val cleanup = rig.dataSource.connection
    try {
      cleanup.autoCommit = false
      val removed =
          cleanup.createStatement().use {
            it.executeUpdate("DELETE FROM run_log_entry") + it.executeUpdate("DELETE FROM run")
          }
      assertTrue(removed > 0)

      val results =
          pool
              .submit<Pair<Any, Any>> {
                // Takes the allow list's exclusive lock and judges every definition again, then an
                // upload, which takes the same lock shared for its last step.
                val change = rig.add("java.util")
                val upload = rig.upload("another")
                change to upload
              }
              .get(60, TimeUnit.SECONDS)

      assertNotNull(results)
    } finally {
      cleanup.rollback()
      cleanup.close()
    }
  }
}
