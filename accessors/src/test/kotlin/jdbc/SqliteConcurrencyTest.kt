package dev.lawlan.runline.accessors.jdbc

import dev.lawlan.runline.accessors.Invalidation
import dev.lawlan.runline.core.ResourceFailure
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Runs that use one `sqlite` file at the same time (WI-1003): there is one writer at a time, and a
 * run that finds the file being written waits for it, until its statement limit, instead of
 * failing; readers are never held up by the writer. And a run that is stopped, by cancellation or
 * by force, stops the statement in the file, rolls back what it had not committed, and lets go of
 * the lock.
 */
class SqliteConcurrencyTest {
  private val rig = SqliteRig()
  private val threads = Executors.newCachedThreadPool()

  @AfterTest
  fun close() {
    threads.shutdownNow()
    rig.close()
  }

  private fun prepared(runs: Int, extra: String = "") =
      List(runs) { rig.host(capacity = runs, extra = extra) }
          .also { rig.update(it.first(), "CREATE TABLE t (run INTEGER, n INTEGER)") }

  @Test
  fun `runs that write to the same file at once all succeed, one writer at a time`() {
    val runs = 6
    val perRun = 40
    val hosts = prepared(runs)
    val go = CountDownLatch(1)

    val all = hosts.mapIndexed { run, host ->
      threads.submit {
        go.await()
        for (n in 1..perRun) rig.update(
            host,
            "INSERT INTO t VALUES (?, ?)",
            listOf(run.toLong(), n.toLong()),
        )
      }
    }
    go.countDown()
    all.forEach { it.get(60, TimeUnit.SECONDS) }

    assertEquals(
        runs.toLong() * perRun,
        rig.query(hosts.first(), "SELECT count(*) AS c FROM t").single()["c"],
    )
    assertEquals(runs, rig.pools.holders("db"))
  }

  @Test
  fun `runs that open a file that is not there yet at the same moment all succeed, and the file is made once`() {
    val runs = 8
    repeat(40) { round ->
      val path = "first-$round.db"
      val hosts = List(runs) { rig.host(path = path, capacity = runs) }
      val go = CountDownLatch(1)

      val all = hosts.mapIndexed { run, host ->
        threads.submit<Long> {
          go.await()
          rig.update(host, "CREATE TABLE IF NOT EXISTS t$run (n INTEGER)")
        }
      }
      go.countDown()
      all.forEach { it.get(60, TimeUnit.SECONDS) }

      val tables =
          rig.external(path) { c ->
            c.createStatement().use { s ->
              s.executeQuery("SELECT count(*) FROM sqlite_master").use {
                it.next()
                it.getLong(1)
              }
            }
          }
      assertEquals(runs.toLong(), tables, "round $round")
      // Every connection has the log and the foreign keys, not only the one that won the race.
      hosts.forEach { host ->
        assertEquals("wal", rig.query(host, "PRAGMA journal_mode").single()["journal_mode"])
        assertEquals(1L, rig.query(host, "PRAGMA foreign_keys").single()["foreign_keys"])
      }
    }
  }

  @Test
  fun `a writer waits for the one that holds the lock instead of failing, and goes on when it is released`() {
    val (holder, waiter) = prepared(2)
    rig.value(holder, "jdbc.begin")
    rig.update(holder, "INSERT INTO t VALUES (1, 1)")

    val started = System.nanoTime()
    val waiting = threads.submit<Long> { rig.update(waiter, "INSERT INTO t VALUES (2, 2)") }
    Thread.sleep(700)
    assertTrue(!waiting.isDone, "the writer did not wait")
    rig.value(holder, "jdbc.commit")

    assertEquals(1L, waiting.get(15, TimeUnit.SECONDS))
    assertTrue((System.nanoTime() - started) / 1_000_000 >= 700)
    assertEquals(2L, rig.query(holder, "SELECT count(*) AS c FROM t").single()["c"])
  }

  @Test
  fun `a writer that waits longer than its statement may run is told it timed out`() {
    val (holder, waiter) = prepared(2, extra = """"timeouts":{"statementMs":700}""")
    rig.value(holder, "jdbc.begin")
    rig.update(holder, "INSERT INTO t VALUES (1, 1)")

    val started = System.nanoTime()
    val failed = assertFailsWith<Failed> { rig.update(waiter, "INSERT INTO t VALUES (2, 2)") }
    val tookMillis = (System.nanoTime() - started) / 1_000_000

    assertEquals(ResourceFailure.TOTAL_TIMEOUT, failed.failure)
    assertTrue(tookMillis in 600..5_000, "it took $tookMillis ms")
    rig.value(holder, "jdbc.commit")
    assertEquals(2L, rig.update(waiter, "INSERT INTO t VALUES (2, 2)") + 1)
  }

  @Test
  fun `readers see what is committed and are not held up by a writer, in the Engine's runs or outside`() {
    val (writer, reader) = prepared(2)
    rig.update(writer, "INSERT INTO t VALUES (0, 0)")
    rig.value(writer, "jdbc.begin")
    rig.update(writer, "INSERT INTO t VALUES (1, 1)")

    val started = System.nanoTime()
    val seenByRun = rig.query(reader, "SELECT count(*) AS c FROM t").single()["c"]
    val seenOutside = rig.external { c ->
      c.createStatement().use { s ->
        s.executeQuery("SELECT count(*) FROM t").use {
          it.next()
          it.getLong(1)
        }
      }
    }

    assertEquals(1L, seenByRun)
    assertEquals(1L, seenOutside)
    assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000, "a reader waited for the writer")
    rig.value(writer, "jdbc.commit")
    assertEquals(2L, rig.query(reader, "SELECT count(*) AS c FROM t").single()["c"])
  }

  private fun waitingFor(what: String, until: () -> Boolean) {
    val deadline = System.nanoTime() + 15_000_000_000L
    while (!until()) {
      check(System.nanoTime() < deadline) { "never saw $what" }
      Thread.sleep(20)
    }
  }

  private val forever =
      "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c) SELECT count(*) AS n FROM c"

  @Test
  fun `a run released by force while a statement runs is cancelled, its transaction is rolled back and the lock is let go`() {
    val (first) = prepared(1)
    rig.value(first, "jdbc.begin")
    rig.update(first, "INSERT INTO t VALUES (1, 1)")
    val running = threads.submit<Failed> { assertFailsWith<Failed> { rig.query(first, forever) } }
    Thread.sleep(500)
    assertTrue(!running.isDone, "the statement ended by itself")

    val started = System.nanoTime()
    first.invalidate("db", Invalidation.FORCE_RELEASED)
    val told = running.get(15, TimeUnit.SECONDS)
    val tookMillis = (System.nanoTime() - started) / 1_000_000

    assertEquals(ResourceFailure.CANCELLED, told.failure)
    assertTrue(tookMillis < 10_000, "the release took $tookMillis ms")
    assertEquals(
        ResourceFailure.FORCE_RELEASED.name,
        rig.call(first, "jdbc.query", rig.statement("SELECT 1"))["failure"],
    )
    // Nothing of the transaction is left, and the lock is free for another writer at once.
    val next = rig.host()
    assertEquals(0L, rig.query(next, "SELECT count(*) AS c FROM t").single()["c"])
    assertEquals(1L, rig.update(next, "INSERT INTO t VALUES (2, 2)"))
    rig.external { c -> c.createStatement().use { it.execute("PRAGMA busy_timeout = 0") } }
  }

  @Test
  fun `a run released by force while it waits for the lock stops waiting at once`() {
    val (holder, waiter) = prepared(2, extra = """"timeouts":{"statementMs":60000}""")
    rig.value(holder, "jdbc.begin")
    rig.update(holder, "INSERT INTO t VALUES (1, 1)")
    val waiting =
        threads.submit<Failed> {
          assertFailsWith<Failed> { rig.update(waiter, "INSERT INTO t VALUES (2, 2)") }
        }
    Thread.sleep(500)
    assertTrue(!waiting.isDone)

    val started = System.nanoTime()
    waiter.invalidate("db", Invalidation.FORCE_RELEASED)
    val tookMillis = (System.nanoTime() - started) / 1_000_000

    assertEquals(ResourceFailure.CANCELLED, waiting.get(15, TimeUnit.SECONDS).failure)
    assertTrue(tookMillis < 10_000, "the release took $tookMillis ms")
    rig.value(holder, "jdbc.commit")
  }

  @Test
  fun `transactions that read and then write wait for each other instead of failing on a stale read`() {
    val (first, second) = prepared(2)
    rig.value(first, "jdbc.begin")
    rig.query(first, "SELECT count(*) AS c FROM t")

    val other = threads.submit {
      rig.value(second, "jdbc.begin")
      rig.query(second, "SELECT count(*) AS c FROM t")
      rig.update(second, "INSERT INTO t VALUES (2, 2)")
      rig.value(second, "jdbc.commit")
    }
    Thread.sleep(500)
    rig.update(first, "INSERT INTO t VALUES (1, 1)")
    rig.value(first, "jdbc.commit")
    other.get(15, TimeUnit.SECONDS)

    assertEquals(2L, rig.query(first, "SELECT count(*) AS c FROM t").single()["c"])
  }

  @Test
  fun `a run that ends with a transaction open leaves nothing of it and no lock`() {
    val (first) = prepared(1)
    rig.value(first, "jdbc.begin")
    rig.update(first, "INSERT INTO t VALUES (1, 1)")

    first.invalidateAll(Invalidation.RUN_ENDED)

    assertEquals(0, rig.pools.activeConnections("db"))
    val next = rig.host()
    assertEquals(0L, rig.query(next, "SELECT count(*) AS c FROM t").single()["c"])
    assertEquals(1L, rig.update(next, "INSERT INTO t VALUES (2, 2)"))
  }
}
