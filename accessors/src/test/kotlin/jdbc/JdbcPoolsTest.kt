package dev.lawlan.runline.accessors.jdbc

import dev.lawlan.runline.accessors.Invalidation
import dev.lawlan.runline.core.ResourceFailure
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The pool behind a `jdbc-pool` resource (WI-48): as large as the runs that may hold the resource
 * can use, made when first needed, shared by the runs that hold the resource now, and replaced by a
 * new one when the resource is changed, while the runs that hold the old one go on with it.
 */
class JdbcPoolsTest {
  private val rig = JdbcRig()
  private val threads = Executors.newCachedThreadPool()

  @AfterTest
  fun close() {
    threads.shutdownNow()
    rig.close()
  }

  private fun pidOf(host: dev.lawlan.runline.accessors.BoundResources) =
      rig.query(host, "SELECT pg_backend_pid() AS pid").single()["pid"]

  private fun applicationOf(host: dev.lawlan.runline.accessors.BoundResources) =
      rig.query(host, "SELECT current_setting('application_name') AS a").single()["a"]

  @Test
  fun `the runs that may hold the resource can all use all of their share at once, and none waits`() {
    val capacity = 3
    val perRun = 2
    val hosts =
        (1..capacity).map {
          rig.host(
              extra = """"connectionsPerRun":$perRun,"timeouts":{"quotaWaitMs":1}""",
              capacity = capacity,
          )
        }

    val started = System.nanoTime()
    val all = hosts.flatMap { host ->
      (1..perRun).map { threads.submit { rig.query(host, "SELECT pg_sleep(1.5)") } }
    }
    val deadline = System.nanoTime() + 15_000_000_000L
    while (rig.db.active("runline") < capacity * perRun) {
      check(System.nanoTime() < deadline) { "never saw ${capacity * perRun} statements at once" }
      Thread.sleep(20)
    }
    val using = rig.pools.activeConnections("db")
    all.forEach { it.get(15, TimeUnit.SECONDS) }
    val tookMillis = (System.nanoTime() - started) / 1_000_000

    assertEquals(capacity * perRun, using)
    assertEquals(capacity * perRun, rig.db.sessions())
    // Everything ran together: the six of them did not take six times as long.
    assertTrue(tookMillis < 6_000, "took $tookMillis ms")
  }

  @Test
  fun `the pool is made when it is first needed, so a resource that cannot be reached still gives its binding`() {
    val unreachable = ServerSocket(0).use { it.localPort }

    val binding = rig.host(port = unreachable)
    val works = rig.host()

    assertEquals(0, rig.db.sessions(), "binding connected")
    val e = assertFailsWith<Failed> { rig.query(binding, "SELECT 1") }
    assertEquals(ResourceFailure.CONNECTION_FAILED, e.failure)
    assertEquals(1L, rig.query(works, "SELECT 1 AS one").single()["one"])
  }

  @Test
  fun `runs that hold the resource now share one pool, and a run that ends leaves its connection for the next`() {
    val first = rig.host(capacity = 2)
    val second = rig.host(capacity = 2)

    val one = pidOf(first)
    val two = pidOf(second)
    first.invalidateAll(Invalidation.RUN_ENDED)
    val third = rig.host(capacity = 2)
    val three = pidOf(third)

    assertNotEquals(one, two, "two runs at once used one session")
    assertEquals(one, three, "the connection the first run left was not reused")
    assertEquals(2, rig.db.sessions(), "more connections than the capacity allows")
  }

  @Test
  fun `after a change the runs that hold the old one go on with it and later runs get a new one`() {
    val old = rig.host(extra = """"properties":{"ApplicationName":"gen-1"}""")
    assertEquals("gen-1", applicationOf(old))

    val later = rig.host(extra = """"properties":{"ApplicationName":"gen-2"}""")

    assertEquals("gen-2", applicationOf(later))
    assertEquals("gen-1", applicationOf(old), "the run that held the resource was moved")
    assertEquals(1, rig.db.sessions("gen-1"))
    assertEquals(1, rig.db.sessions("gen-2"))
    later.invalidateAll(Invalidation.RUN_ENDED)
    val third = rig.host(extra = """"properties":{"ApplicationName":"gen-2"}""")
    assertEquals(1, rig.db.sessions("gen-2"))
    pidOf(third)
    assertEquals(1, rig.db.sessions("gen-2"), "the same generation did not share its pool")
  }

  @Test
  fun `a changed capacity, port or other setting is a new generation, and so is a changed password`() {
    val a = rig.host(capacity = 2)
    val sameAgain = rig.host(capacity = 2)
    val capacity = rig.host(capacity = 3)
    val timeout = rig.host(extra = """"timeouts":{"statementMs":1000}""", capacity = 2)
    val password = rig.host(password = rig.password + "-rotated")

    pidOf(a)
    pidOf(sameAgain)
    pidOf(capacity)
    pidOf(timeout)
    // The same settings are one pool of two connections' room, the others have pools of their own.
    assertEquals(4, rig.db.sessions())
    // A password the account does not have is a generation of its own, and is denied.
    val denied = assertFailsWith<Failed> { pidOf(password) }
    assertEquals(ResourceFailure.DENIED, denied.failure)
  }

  @Test
  fun `a generation is closed when its last holder is done, and not before`() {
    val first = rig.host(extra = """"properties":{"ApplicationName":"gen-1"}""", capacity = 2)
    val second = rig.host(extra = """"properties":{"ApplicationName":"gen-1"}""", capacity = 2)
    pidOf(first)
    pidOf(second)
    val later = rig.host(extra = """"properties":{"ApplicationName":"gen-2"}""")
    pidOf(later)
    assertEquals(2, rig.db.sessions("gen-1"))

    first.invalidateAll(Invalidation.RUN_ENDED)
    assertEquals(2, rig.db.sessions("gen-1"), "closed with a holder left")
    second.invalidateAll(Invalidation.RUN_ENDED)

    assertEquals(0, rig.db.sessions("gen-1"), "the old generation is still open")
    assertEquals(1, rig.db.sessions("gen-2"))
    assertEquals(1L, rig.query(later, "SELECT 1 AS one").single()["one"])
  }

  @Test
  fun `the current generation keeps its connections for the next run, and closing the pools closes them`() {
    val first = rig.host()
    pidOf(first)
    first.invalidateAll(Invalidation.RUN_ENDED)
    assertEquals(1, rig.db.sessions())

    val next = rig.host()
    assertEquals(1, rig.db.sessions(), "a connection was opened again")
    pidOf(next)
    rig.pools.close()

    assertEquals(0, rig.db.sessions())
  }

  @Test
  fun `removing a resource closes the connections its pool keeps for the next run`() {
    val first = rig.host()
    pidOf(first)
    first.invalidateAll(Invalidation.RUN_ENDED)
    assertEquals(1, rig.db.sessions(user = rig.role))

    rig.pools.remove("db")

    assertEquals(0, rig.db.sessions(user = rig.role), "the pool of a removed resource is open")
  }

  @Test
  fun `the connections in use are those of every generation of the resource`() {
    val old = rig.host(extra = """"properties":{"ApplicationName":"gen-1"}""")
    val new = rig.host(extra = """"properties":{"ApplicationName":"gen-2"}""")
    val running = listOf(old, new).map { threads.submit { rig.query(it, "SELECT pg_sleep(1.5)") } }
    val deadline = System.nanoTime() + 15_000_000_000L
    while (rig.db.sessions() < 2 || rig.pools.activeConnections("db") < 2) {
      check(System.nanoTime() < deadline) { "never saw both in use" }
      Thread.sleep(20)
    }

    assertEquals(2, rig.pools.activeConnections("db"))
    running.forEach { it.get(15, TimeUnit.SECONDS) }
    listOf(old, new).forEach { it.invalidateAll(Invalidation.RUN_ENDED) }
    assertEquals(0, rig.pools.activeConnections("db"))
  }
}
