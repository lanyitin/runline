package dev.lawlan.runline.engine.health

import dev.lawlan.runline.engine.db.probeDataSourceOf
import dev.lawlan.runline.engine.support.MutableClock
import dev.lawlan.runline.engine.support.StoppablePostgres
import dev.lawlan.runline.engine.support.awaitCondition
import dev.lawlan.runline.engine.support.getWithin
import java.time.Duration
import java.util.concurrent.Executors
import kotlin.test.*

/** The database check against a real PostgreSQL that is stopped and started (ADR-018). */
class DatabaseCheckTest {
  private val ttl = Duration.ofSeconds(2)
  private val clock = MutableClock()
  private val postgres = StoppablePostgres.startMigrated()
  private val check =
      DatabaseCheck(
          probeDataSourceOf(postgres.database, timeoutSeconds = 2),
          clock,
          cacheFor = ttl,
      )

  @AfterTest fun stop() = postgres.close()

  @Test
  fun `a database that answers is ok, one that is stopped is failed with a reason for the log`() {
    assertEquals(CheckState.OK, check.check().state)

    postgres.stop()
    clock.advance(ttl)
    val failed = check.check()

    assertEquals(CheckState.FAILED, failed.state)
    assertNotNull(failed.reason)
  }

  @Test
  fun `the answer is remembered for a few seconds and the database is asked again after that`() {
    assertEquals(CheckState.OK, check.check().state)
    postgres.stop()

    // Within the time, nobody asks the database: the answer is the one it gave.
    clock.advance(ttl.minusMillis(1))
    assertEquals(CheckState.OK, check.check().state)

    clock.advance(Duration.ofMillis(1))
    assertEquals(CheckState.FAILED, check.check().state)
  }

  @Test
  fun `probes that arrive together do not become queries, and the check recovers with the database`() {
    assertEquals(CheckState.OK, check.check().state)
    postgres.stop()
    val pool = Executors.newFixedThreadPool(16)
    try {
      val answers = (1..16).map { pool.submit<CheckState> { check.check().state } }
      assertEquals(
          setOf(CheckState.OK),
          answers.map { it.getWithin("a probe to be answered") }.toSet(),
      )
    } finally {
      pool.shutdownNow()
    }

    postgres.start()
    clock.advance(ttl)
    awaitCondition("the check to find the database back") {
      clock.advance(ttl)
      check.check().state == CheckState.OK
    }
  }
}
