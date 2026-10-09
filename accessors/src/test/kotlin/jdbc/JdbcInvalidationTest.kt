package dev.lawlan.runline.accessors.jdbc

import dev.lawlan.runline.accessors.BoundResources
import dev.lawlan.runline.accessors.Invalidation
import dev.lawlan.runline.core.ResourceFailure
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * What a run leaves behind when it ends, is released by force or is cancelled (WI-48): nothing. The
 * pool has one connection here, so the next run gets the very connection the last one used, and
 * must find it as new: no transaction, no setting, no role, no temporary table, no prepared
 * statement, no lock, no listener. A connection that cannot be shown to be clean is closed.
 */
class JdbcInvalidationTest {
  private val rig = JdbcRig()
  private val threads = Executors.newCachedThreadPool()

  @AfterTest
  fun close() {
    threads.shutdownNow()
    rig.close()
  }

  private fun scalar(host: BoundResources, sql: String) =
      rig.query(host, sql).single().values.first()

  private fun dirty(host: BoundResources) {
    rig.db.execute("CREATE ROLE ${rig.role}_sub NOLOGIN")
    rig.db.execute("GRANT ${rig.role}_sub TO ${rig.role}")
    rig.update(host, "SET search_path TO pg_catalog")
    rig.update(host, "SET ROLE ${rig.role}_sub")
    rig.update(host, "SET statement_timeout = 12345")
    rig.update(host, "CREATE TEMP TABLE scratch (a int)")
    rig.update(host, "PREPARE leftover AS SELECT 1")
    rig.query(host, "SELECT pg_advisory_lock(4242)")
    rig.update(host, "LISTEN leftover_channel")
  }

  private fun assertClean(host: BoundResources) {
    assertTrue(scalar(host, "SHOW search_path").toString().contains("public"), "search_path")
    assertEquals(rig.role, scalar(host, "SELECT current_user"), "role")
    assertEquals(null, scalar(host, "SELECT to_regclass('pg_temp.scratch')::text"), "temp table")
    assertEquals(0L, scalar(host, "SELECT count(*) FROM pg_prepared_statements"), "prepared")
    assertEquals(true, scalar(host, "SELECT pg_try_advisory_lock(4242)"), "advisory lock")
    assertEquals(0L, scalar(host, "SELECT count(*) FROM pg_listening_channels()"), "listeners")
    assertEquals("0", scalar(host, "SHOW statement_timeout"), "statement_timeout")
    assertEquals("UTC", scalar(host, "SHOW TimeZone"), "time zone")
  }

  @Test
  fun `a run that ends leaves the next run a connection as new`() {
    val first = rig.host()
    val pid = scalar(first, "SELECT pg_backend_pid()")
    dirty(first)
    first.invalidateAll(Invalidation.RUN_ENDED)

    val next = rig.host()

    assertEquals(pid, scalar(next, "SELECT pg_backend_pid()"), "it was not the same connection")
    assertClean(next)
  }

  @Test
  fun `what the administrator set for the connection is set again after each reset`() {
    rig.db.execute("CREATE SCHEMA etl")
    rig.db.execute("GRANT ALL ON SCHEMA etl TO ${rig.role}")
    val extra = """"properties":{"ApplicationName":"nightly","currentSchema":"etl,public"}"""
    val first = rig.host(extra)
    assertEquals("nightly", scalar(first, "SHOW application_name"))
    assertEquals("etl,public", scalar(first, "SHOW search_path").toString().replace(" ", ""))
    first.invalidateAll(Invalidation.RUN_ENDED)

    val next = rig.host(extra)

    assertEquals(1, rig.db.sessions("nightly"))
    assertEquals("nightly", scalar(next, "SHOW application_name"))
    assertEquals("etl,public", scalar(next, "SHOW search_path").toString().replace(" ", ""))
    assertEquals("UTC", scalar(next, "SHOW TimeZone"))
  }

  @Test
  fun `a transaction left open is rolled back, and its connection is given back`() {
    val first = rig.host()
    rig.update(first, "CREATE TABLE t (a int)")
    rig.value(first, "jdbc.begin")
    rig.update(first, "INSERT INTO t VALUES (1)")

    first.invalidateAll(Invalidation.RUN_ENDED)

    assertEquals("0", rig.db.scalar("SELECT count(*) FROM t"))
    assertEquals(0, rig.db.idleInTransaction("runline"))
    assertEquals(0, rig.pools.activeConnections("db"))
    val next = rig.host()
    assertEquals(0L, scalar(next, "SELECT count(*) FROM t"))
  }

  @Test
  fun `a run released by force while a statement runs is cancelled, and leaves nothing either`() {
    val first = rig.host()
    dirty(first)
    val running =
        threads.submit<Failed> {
          assertFailsWith<Failed> { rig.query(first, "SELECT pg_sleep(60)") }
        }
    val deadline = System.nanoTime() + 15_000_000_000L
    while (rig.db.active("runline") < 1) {
      check(System.nanoTime() < deadline) { "the statement never ran" }
      Thread.sleep(20)
    }

    val started = System.nanoTime()
    first.invalidate("db", Invalidation.FORCE_RELEASED)
    val tookMillis = (System.nanoTime() - started) / 1_000_000
    val told = running.get(15, TimeUnit.SECONDS)

    assertEquals(ResourceFailure.CANCELLED, told.failure)
    assertTrue(tookMillis < 10_000, "the release took $tookMillis ms")
    assertEquals(
        ResourceFailure.FORCE_RELEASED.name,
        rig.call(first, "jdbc.query", rig.statement("SELECT 1"))["failure"],
    )
    assertEquals(0, rig.db.active("runline"), "the statement is still running in the database")
    val next = rig.host()
    assertClean(next)
  }

  @Test
  fun `a connection whose reset fails is closed and not given to another run, and the failure is said`() {
    val failing =
        object : JdbcProfile by PostgresProfile {
          override val resetStatements = listOf("SELECT no_such_function_for_reset()")
        }
    val profiles = JdbcProfiles(listOf(failing))
    JdbcRig(profiles).use { local ->
      val first = local.host()
      val pid = scalar2(local, first, "SELECT pg_backend_pid()")
      val failure =
          assertFailsWith<RuntimeException> { first.invalidateAll(Invalidation.RUN_ENDED) }
      assertTrue("42883" in failure.message.orEmpty(), "${failure.message}")

      assertEquals(0, local.db.sessions(), "the connection was kept")
      val next = local.host()
      assertNotEquals(pid, scalar2(local, next, "SELECT pg_backend_pid()"))
    }
  }

  private fun scalar2(local: JdbcRig, host: BoundResources, sql: String) =
      local.query(host, sql).single().values.first()

  @Test
  fun `a profile with nothing to reset must say so`() {
    val silent =
        object : JdbcProfile by PostgresProfile {
          override val kind = "silent"
          override val resetStatements: List<String> = emptyList()
        }
    val declared =
        object : JdbcProfile by PostgresProfile {
          override val kind = "declared"
          override val resetStatements: List<String> = emptyList()
          override val resetNotNeeded = true
        }

    assertFailsWith<IllegalArgumentException> { JdbcProfiles(listOf(silent)) }
    JdbcProfiles(listOf(declared))
  }

  /** A real TCP relay that can be made to stop passing bytes without closing, like a dead route. */
  private class FreezableProxy(private val targetPort: Int) : AutoCloseable {
    private val server = java.net.ServerSocket(0)
    @Volatile var frozen = false
    val port = server.localPort
    private val sockets = java.util.concurrent.CopyOnWriteArrayList<java.net.Socket>()

    init {
      Thread.ofPlatform().daemon().start {
        try {
          while (true) {
            val client = server.accept()
            val upstream = java.net.Socket(RealPostgres.host, targetPort)
            sockets += client
            sockets += upstream
            pipe(client, upstream)
            pipe(upstream, client)
          }
        } catch (e: java.io.IOException) {
          // closed
        }
      }
    }

    private fun pipe(from: java.net.Socket, to: java.net.Socket) {
      Thread.ofPlatform().daemon().start {
        try {
          val buffer = ByteArray(8192)
          while (true) {
            val n = from.getInputStream().read(buffer)
            if (n < 0) break
            while (frozen) Thread.sleep(20)
            to.getOutputStream().write(buffer, 0, n)
          }
        } catch (e: Exception) {
          // closed
        }
      }
    }

    override fun close() {
      server.close()
      sockets.forEach { runCatching { it.close() } }
    }
  }

  @Test
  fun `a forced release returns in bounded time even when the network to the database is dead`() {
    FreezableProxy(RealPostgres.port).use { proxy ->
      val host = rig.host(port = proxy.port)
      assertEquals(1L, rig.query(host, "SELECT 1 AS one").single()["one"])
      val running =
          threads.submit<Failed> {
            assertFailsWith<Failed> { rig.query(host, "SELECT pg_sleep(20)") }
          }
      Thread.sleep(500)
      proxy.frozen = true

      val started = System.nanoTime()
      host.invalidate("db", Invalidation.FORCE_RELEASED)
      val tookMillis = (System.nanoTime() - started) / 1_000_000
      val told = running.get(15, TimeUnit.SECONDS)

      assertEquals(ResourceFailure.CANCELLED, told.failure)
      assertTrue(tookMillis < 8_000, "the release took $tookMillis ms")
    }
  }

  @Test
  fun `a statement that waits for the run's share is let go when the run is released`() {
    val host = rig.host(extra = """"timeouts":{"quotaWaitMs":30000}""")
    val first =
        threads.submit<Failed> {
          assertFailsWith<Failed> { rig.query(host, "SELECT pg_sleep(30)") }
        }
    while (rig.db.active("runline") < 1) Thread.sleep(20)
    val waiting = threads.submit<Failed> { assertFailsWith<Failed> { rig.query(host, "SELECT 1") } }
    Thread.sleep(300)

    host.invalidate("db", Invalidation.FORCE_RELEASED)

    assertEquals(ResourceFailure.CANCELLED, first.get(15, TimeUnit.SECONDS).failure)
    val second = waiting.get(15, TimeUnit.SECONDS).failure
    assertTrue(
        second == ResourceFailure.CANCELLED || second == ResourceFailure.FORCE_RELEASED,
        "$second",
    )
  }
}
