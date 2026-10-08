package dev.lawlan.runline.accessors.jdbc

import dev.lawlan.runline.accessors.Invalidation
import dev.lawlan.runline.accessors.fake.FreezableForward
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Giving a connection back cleans it first (WI-48), and the cleaning talks to the database. When
 * the network to the database stops answering, that has its own limit (WI-62): the end of a run
 * cannot hang on it, and a cleaning that did not finish is said, never passed over in silence.
 */
class JdbcResetLimitTest {
  private val rig = JdbcRig()
  private val forward = FreezableForward(RealPostgres.host, RealPostgres.port)

  @AfterTest
  fun close() {
    forward.close()
    rig.close()
  }

  @Test
  fun `a connection whose network stops answering is given up within the limit of its reset, and that is said`() {
    val host = rig.host(hostName = forward.host, port = forward.port)
    rig.value(host, "jdbc.begin")
    rig.query(host, "SELECT 1")
    forward.freeze()

    val started = System.nanoTime()
    val ending = CompletableFuture.runAsync { host.invalidateAll(Invalidation.RUN_ENDED) }
    val failure =
        try {
          ending.get(JdbcPools.RESET_LIMIT_MILLIS + 15_000L, TimeUnit.MILLISECONDS)
          fail("giving back a connection that could not be cleaned was reported as done")
        } catch (e: TimeoutException) {
          fail("giving back the connection still hangs, past the limit of its reset")
        } catch (e: ExecutionException) {
          e.cause!!
        }
    val millis = (System.nanoTime() - started) / 1_000_000

    assertTrue(millis < JdbcPools.RESET_LIMIT_MILLIS + 5_000, "it took $millis ms")
    assertTrue(failure.message.orEmpty().isNotEmpty(), "$failure")
    assertTrue(rig.password !in failure.toString(), "the password is never part of it")
    forward.thaw()
    // The connection was closed, not kept: the next run gets a new one that works.
    assertEquals(0, rig.pools.activeConnections("db"))
    val next = rig.host(hostName = forward.host, port = forward.port)
    assertEquals(1L, rig.query(next, "SELECT 1 AS one").single()["one"])
  }
}
