package dev.lawlan.runline.engine.health

import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource

/**
 * The database answers a light query. The connection comes from [dataSource], which must give up
 * within a short time of its own (the check has to answer before the platform's probe times out),
 * and it is not one a run uses. The answer is remembered for [cacheFor]: probes that arrive
 * together, or one after the other, are not turned into queries against the database.
 */
class DatabaseCheck(
    private val dataSource: DataSource,
    private val clock: Clock,
    private val cacheFor: Duration,
) : ReadinessCheck {
  override val name = "database"

  private var cached: CheckResult? = null
  private var cachedAt: Instant = Instant.MIN

  @Synchronized
  override fun check(): CheckResult {
    val now = clock.instant()
    cached?.let { if (Duration.between(cachedAt, now) < cacheFor) return it }
    val result =
        try {
          dataSource.connection.use { c -> c.createStatement().use { it.execute("SELECT 1") } }
          CheckResult.OK
        } catch (e: Exception) {
          CheckResult.failed("the database did not answer: ${e.message}")
        }
    cached = result
    cachedAt = now
    return result
  }
}
