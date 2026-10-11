package dev.lawlan.runline.engine.oidc

import dev.lawlan.runline.engine.auth.Role
import dev.lawlan.runline.engine.config.RetentionSettings
import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.retention.PostgresRetentionStore
import dev.lawlan.runline.engine.retention.RetentionCleaner
import dev.lawlan.runline.engine.retention.RetentionTelemetry
import dev.lawlan.runline.engine.support.MutableClock
import dev.lawlan.runline.engine.support.PostgresTestContainer
import dev.lawlan.runline.engine.support.migratedDatabase
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.*

/**
 * The existing clean-up removes the sessions and the sign-ins that expired (WI-1200,
 * 06-data-model), in batches, with its other steps. A session was invalid before this: nothing here
 * is needed for that, which the store tests show.
 */
class OidcRetentionTest {
  private val database = migratedDatabase()
  private val dataSource = dataSourceOf(database)
  private val sessions = PostgresSessionStore(dataSource)
  private val attempts = PostgresLoginAttemptStore(dataSource)
  private val clock = MutableClock(Instant.parse("2026-10-10T10:00:00Z"))
  private val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
  private val idle = Duration.ofHours(8)
  private val absolute = Duration.ofDays(7)
  private val metrics = InMemoryMetricReader.create()
  private val telemetry =
      RetentionTelemetry(
          OpenTelemetrySdk.builder()
              .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metrics).build())
              .build()
      )
  private val settings =
      RetentionSettings(
          runRetention = Duration.ofDays(30),
          logRetention = Duration.ofDays(30),
          webhookFiringRetention = Duration.ofDays(7),
          cronFiringRetention = Duration.ofDays(30),
          interval = Duration.ofHours(1),
          batchSize = 1000,
          sessionIdle = idle,
      )

  private fun cleaner(settings: RetentionSettings = this.settings) =
      RetentionCleaner(PostgresRetentionStore(dataSource), settings, clock, telemetry)

  private fun hash(id: String) = MessageDigest.getInstance("SHA-256").digest(id.toByteArray())

  /** A session made [age] ago and not used since. */
  private fun session(id: String, age: Duration, lifetime: Duration = absolute) {
    val created = now - age
    sessions.signIn(
        SignInIdentity("https://auth.example.test/", "sub-$id", "user-$id", "User $id"),
        NewSession(hash(id), Role.DEVELOPER, "csrf", byteArrayOf(1), created, created + lifetime),
        created,
    )
  }

  private fun alive(id: String) = sessions.resume(hash(id), now, idle) != null

  private fun rows(table: String): Int =
      PostgresTestContainer.connect(database).use { c ->
        c.createStatement().use { s ->
          s.executeQuery("SELECT count(*) FROM $table").use {
            it.next()
            it.getInt(1)
          }
        }
      }

  private fun counter(name: String): Long =
      metrics
          .collectAllMetrics()
          .singleOrNull { it.name == name }
          ?.longSumData
          ?.points
          ?.sumOf { it.value } ?: 0L

  @Test
  fun `sessions past their expiry or their idle time are removed, the others are kept`() {
    session("fresh", Duration.ofHours(1))
    session("past-expiry", Duration.ofDays(8))
    session("past-idle", Duration.ofHours(9))

    val report = cleaner().clean()

    assertEquals(2, report.sessions)
    assertEquals(1, rows("user_session"))
    assertTrue(alive("fresh"))
    // The people stay, whatever became of their sessions.
    assertEquals(3, rows("oidc_user"))
  }

  @Test
  fun `a session that was used lately is kept, though it is old`() {
    session("busy", Duration.ofHours(30), lifetime = Duration.ofDays(7))
    sessions.resume(hash("busy"), now - Duration.ofHours(1), Duration.ofDays(7))

    assertEquals(0, cleaner().clean().sessions)
    assertTrue(alive("busy"))
  }

  @Test
  fun `sign-ins that were not finished in time are removed`() {
    fun attempt(state: String, age: Duration) =
        attempts.save(
            LoginAttempt(state, "n", "v", "/", now - age),
            Int.MAX_VALUE,
            now - LOGIN_ATTEMPT_TTL,
        )
    attempt("old", Duration.ofMinutes(11))
    attempt("fresh", Duration.ofMinutes(5))

    val report = cleaner().clean()

    assertEquals(1, report.loginAttempts)
    assertEquals(1, rows("login_attempt"))
    assertNotNull(attempts.consume("fresh", now - LOGIN_ATTEMPT_TTL))
  }

  @Test
  fun `a backlog is removed in batches, and a second pass has nothing to do`() {
    repeat(5) { session("old-$it", Duration.ofDays(9)) }

    val report = cleaner(settings.copy(batchSize = 2)).clean()

    assertEquals(5, report.sessions)
    assertEquals(0, rows("user_session"))
    assertEquals(0, cleaner().clean().sessions)
  }

  @Test
  fun `the metrics say how many sessions and sign-ins were removed`() {
    session("old", Duration.ofDays(9))
    attempts.save(
        LoginAttempt("s", "n", "v", "/", now - Duration.ofHours(1)),
        Int.MAX_VALUE,
        now - Duration.ofHours(2),
    )

    cleaner().clean()

    assertEquals(1L, counter("runline.retention.sessions.removed"))
    assertEquals(1L, counter("runline.retention.login_attempts.removed"))
  }
}
