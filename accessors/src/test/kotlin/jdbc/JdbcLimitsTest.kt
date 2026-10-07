package dev.lawlan.runline.accessors.jdbc

import dev.lawlan.runline.core.ResourceFailure
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The limits an administrator puts on a `jdbc-pool` resource (WI-48): how long a statement may run,
 * how much an answer may hold, and how many connections a run may use at once.
 */
class JdbcLimitsTest {
  private val rig = JdbcRig()
  private val threads = Executors.newCachedThreadPool()

  @AfterTest
  fun close() {
    threads.shutdownNow()
    rig.close()
  }

  private fun millisOf(block: () -> Unit): Long {
    val started = System.nanoTime()
    block()
    return (System.nanoTime() - started) / 1_000_000
  }

  @Test
  fun `a statement that runs longer than the limit is cut off and the connection is as good as before`() {
    val host = rig.host(extra = """"timeouts":{"statementMs":500}""")

    val took = millisOf {
      val e = assertFailsWith<Failed> { rig.query(host, "SELECT pg_sleep(30)") }
      assertEquals(ResourceFailure.TOTAL_TIMEOUT, e.failure)
    }

    assertTrue(took in 400..10_000, "took $took ms")
    assertEquals(1L, rig.query(host, "SELECT 1 AS one").single()["one"])
    // The database stopped it too: nothing is left running there.
    assertEquals(0, rig.db.active("runline"))
  }

  @Test
  fun `a statement of a transaction that runs too long leaves the transaction to be rolled back`() {
    val host = rig.host(extra = """"timeouts":{"statementMs":500}""")
    rig.update(host, "CREATE TABLE t (a int)")
    rig.value(host, "jdbc.begin")
    rig.update(host, "INSERT INTO t VALUES (1)")

    val e = assertFailsWith<Failed> { rig.query(host, "SELECT pg_sleep(30)") }
    rig.value(host, "jdbc.rollback")

    assertEquals(ResourceFailure.TOTAL_TIMEOUT, e.failure)
    assertEquals("0", rig.db.scalar("SELECT count(*) FROM t"))
  }

  @Test
  fun `an answer of more rows than the limit is refused whole, one of exactly the limit is not`() {
    val host = rig.host(extra = """"maxRows":5""")

    val exactly = rig.query(host, "SELECT * FROM generate_series(1, 5)")
    val e = assertFailsWith<Failed> { rig.query(host, "SELECT * FROM generate_series(1, 6)") }

    assertEquals(5, exactly.size)
    assertEquals(ResourceFailure.RESPONSE_TOO_LARGE, e.failure)
  }

  @Test
  fun `an answer that holds more text and bytes than the limit is refused`() {
    val host = rig.host(extra = """"maxResponseBytes":1000""")

    val small = rig.query(host, "SELECT repeat('x', 900) AS s")
    val text = assertFailsWith<Failed> { rig.query(host, "SELECT repeat('x', 2000) AS s") }
    val bytes =
        assertFailsWith<Failed> { rig.query(host, "SELECT decode(repeat('ab', 1500), 'hex') AS b") }
    val many =
        assertFailsWith<Failed> {
          rig.query(host, "SELECT repeat('x', 100) FROM generate_series(1, 20)")
        }

    assertEquals(900, (small.single()["s"] as String).length)
    assertEquals(ResourceFailure.RESPONSE_TOO_LARGE, text.failure)
    assertEquals(ResourceFailure.RESPONSE_TOO_LARGE, bytes.failure)
    assertEquals(ResourceFailure.RESPONSE_TOO_LARGE, many.failure)
  }

  @Test
  fun `with one connection a second statement of the run waits for the first and gives up at its limit`() {
    val host = rig.host(extra = """"timeouts":{"quotaWaitMs":300}""")
    val started = CountDownLatch(1)
    val first = threads.submit {
      started.countDown()
      rig.query(host, "SELECT pg_sleep(2)")
    }
    started.await()
    awaitActive(1)

    val e = assertFailsWith<Failed> { rig.query(host, "SELECT 1") }

    assertEquals(ResourceFailure.QUOTA_WAIT_TIMEOUT, e.failure)
    first.get(10, TimeUnit.SECONDS)
    assertEquals(listOf(ResourceFailure.QUOTA_WAIT_TIMEOUT), rig.observer.acquireFailures)
    // The share is given back: the next statement is run.
    assertEquals(1L, rig.query(host, "SELECT 1 AS one").single()["one"])
  }

  @Test
  fun `with one connection a second statement that can wait is run when the first is done`() {
    val host = rig.host(extra = """"timeouts":{"quotaWaitMs":20000}""")
    val first = threads.submit { rig.query(host, "SELECT pg_sleep(1)") }
    awaitActive(1)

    val took = millisOf { assertEquals(1L, rig.query(host, "SELECT 1 AS one").single()["one"]) }

    first.get(10, TimeUnit.SECONDS)
    assertTrue(took >= 300, "it did not wait: $took ms")
    assertEquals(1, rig.db.sessions(), "one connection was enough for both")
  }

  @Test
  fun `with two connections two statements of the run are in the database at once`() {
    val host = rig.host(extra = """"connectionsPerRun":2""")
    val both = listOf(1, 2).map { threads.submit { rig.query(host, "SELECT pg_sleep(1.5)") } }

    awaitActive(2)
    val using = rig.pools.activeConnections("db")

    both.forEach { it.get(10, TimeUnit.SECONDS) }
    assertEquals(2, using)
    // The run holds its connections until it is done with the resource.
    assertEquals(2, rig.pools.activeConnections("db"))
    host.invalidateAll(dev.lawlan.runline.accessors.Invalidation.RUN_ENDED)
    assertEquals(0, rig.pools.activeConnections("db"))
  }

  @Test
  fun `the statements of an open transaction are not a second share`() {
    val host = rig.host(extra = """"timeouts":{"quotaWaitMs":300}""")
    rig.value(host, "jdbc.begin")
    val outcomes = AtomicReference<List<Any?>>()
    val other = threads.submit { outcomes.set(rig.query(host, "SELECT 2 AS n").map { it["n"] }) }

    rig.update(host, "CREATE TEMP TABLE scratch (a int)")
    other.get(10, TimeUnit.SECONDS)
    rig.value(host, "jdbc.commit")

    assertEquals(listOf<Any?>(2L), outcomes.get())
    assertNotNull(rig.observer.finished.firstOrNull { it.operation == "jdbc.begin" })
  }

  @Test
  fun `the observer is told of each statement, its time and its category and never of what it said`() {
    val host = rig.host()

    rig.query(host, "SELECT 1")
    assertFailsWith<Failed> { rig.query(host, "SELECT * FROM nowhere_at_all") }
    rig.update(host, "CREATE TABLE t (a int)")

    val seen = rig.observer.finished
    assertEquals(
        listOf("jdbc.query", "jdbc.query", "jdbc.update"),
        seen.map { it.operation },
    )
    assertEquals(listOf(null, ResourceFailure.SQL_ERROR, null), seen.map { it.failure })
    assertTrue(seen.all { it.resource == "db" && it.millis >= 0 })
  }

  /** Waits until [count] statements of the rig are running in the database. */
  private fun awaitActive(count: Int) {
    val deadline = System.nanoTime() + 15_000_000_000L
    while (rig.db.active("runline") < count) {
      check(System.nanoTime() < deadline) { "never saw $count statements running" }
      Thread.sleep(20)
    }
  }
}
