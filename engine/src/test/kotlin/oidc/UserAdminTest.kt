package dev.lawlan.runline.engine.oidc

import dev.lawlan.runline.engine.auth.ApiIdentity
import dev.lawlan.runline.engine.auth.AuthSource
import dev.lawlan.runline.engine.auth.Role
import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.support.CapturedLogs
import dev.lawlan.runline.engine.support.MutableClock
import dev.lawlan.runline.engine.support.migratedDatabase
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import java.time.Duration
import kotlin.test.*

/**
 * What the administration of people and sessions does and says (WI-1205): the rule about the names
 * of API tokens, and the log lines and counters of each act, with a real PostgreSQL.
 */
class UserAdminTest {
  private val dataSource = dataSourceOf(migratedDatabase())
  private val sessions = PostgresSessionStore(dataSource)
  private val clock = MutableClock()
  private val metrics = InMemoryMetricReader.create()
  private val logs = CapturedLogs()
  private val otel =
      OpenTelemetrySdk.builder()
          .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metrics).build())
          .build()
  private val admin =
      UserAdmin(
          PostgresUserAdminStore(dataSource),
          tokenNames = setOf("alice", "root"),
          idle = Duration.ofHours(8),
          clock = clock,
          telemetry = AuthTelemetry(otel),
      )
  private val by = ApiIdentity("root", Role.ADMIN, AuthSource.TOKEN)

  @AfterTest fun close() = logs.close()

  private fun signIn(name: String, id: String) {
    val now = clock.instant()
    sessions.signIn(
        SignInIdentity("https://auth.example.test/", "sub-$name", name, name),
        NewSession(
            SessionIds.hash(id),
            Role.DEVELOPER,
            "csrf",
            byteArrayOf(1),
            now,
            now + Duration.ofDays(7),
        ),
        now,
    )
  }

  private fun counter(name: String): Long =
      metrics
          .collectAllMetrics()
          .filter { it.name == name }
          .flatMap { it.longSumData.points }
          .sumOf { it.value }

  @Test
  fun `disabling and enabling say who, are logged and counted`() {
    signIn("ann", "id-1")
    signIn("ann", "id-2")

    val disabled = assertIs<UserChange.Changed>(admin.disable("ann", by))
    assertEquals(2, disabled.sessionsEnded)
    assertTrue(disabled.user.disabled)
    assertEquals("root", disabled.user.statusChangedBy)
    val enabled = assertIs<UserChange.Changed>(admin.enable("ann", by))
    assertFalse(enabled.user.disabled)

    val lines = logs.lines.filter { it.contains("dev.lawlan.runline.engine.oidc.UserAdmin") }
    assertTrue(
        lines.any {
          it.contains("ann") && it.contains("disabled") && it.contains("root") && it.contains("2")
        },
        "$lines",
    )
    assertTrue(
        lines.any { it.contains("ann") && it.contains("enabled") && it.contains("root") },
        "$lines",
    )
    assertEquals(1L, counter("runline.auth.users.disabled"))
    assertEquals(1L, counter("runline.auth.users.enabled"))
    assertEquals(2L, counter("runline.auth.sessions.ended"))
  }

  @Test
  fun `a name of an API token is not a person who can be disabled, an unknown name is not found`() {
    signIn("ann", "id-1")

    assertEquals(UserChange.TokenName, admin.disable("alice", by))
    assertEquals(UserChange.TokenName, admin.enable("root", by))
    assertEquals(UserChange.NotFound, admin.disable("ghost", by))
    assertEquals(0L, counter("runline.auth.users.disabled"))
  }

  @Test
  fun `ending one or all sessions is logged and counted, with no handle or identifier in the line`() {
    signIn("ann", "id-1")
    signIn("ann", "id-2")
    signIn("ann", "id-3")
    val first = admin.sessions("ann")!!.first()

    assertTrue(admin.endSession("ann", first.ref, by))
    assertFalse(admin.endSession("ann", first.ref, by))
    assertEquals(2, admin.endAll("ann", by))
    assertNull(admin.endAll("ghost", by))

    assertEquals(3L, counter("runline.auth.sessions.ended"))
    val lines = logs.lines.filter { it.contains("dev.lawlan.runline.engine.oidc.UserAdmin") }
    assertTrue(lines.any { it.contains("ann") && it.contains("root") }, "$lines")
    assertTrue(lines.none { it.contains(first.ref) || it.contains("id-1") }, "$lines")
  }
}
