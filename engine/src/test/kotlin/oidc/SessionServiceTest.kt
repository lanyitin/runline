package dev.lawlan.runline.engine.oidc

import dev.lawlan.runline.engine.auth.Role
import dev.lawlan.runline.engine.config.SessionSettings
import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.secret.SecretValue
import dev.lawlan.runline.engine.support.CapturedLogs
import dev.lawlan.runline.engine.support.MutableClock
import dev.lawlan.runline.engine.support.migratedDatabase
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import java.time.Duration
import kotlin.test.*

/**
 * The life of a session (WI-1200, 5 and 6): valid until the idle time or the absolute time is up,
 * counted by the settings and the clock, with no clean-up needed; ended by sign-out, which may be
 * done again.
 */
class SessionServiceTest {
  private val database = migratedDatabase()
  private val dataSource = dataSourceOf(database)
  private val store = PostgresSessionStore(dataSource)
  private val clock = MutableClock()
  private val settings =
      SessionSettings(
          idle = Duration.ofHours(1),
          absolute = Duration.ofHours(5),
          revalidate = Duration.ofDays(30),
          revalidateGrace = Duration.ofMinutes(15),
          encryptionKey = SecretValue("unused"),
      )
  private val oidcSettings =
      dev.lawlan.runline.engine.config.OidcSettings(
          issuer = "http://localhost:1/realms/none",
          clientId = "runline",
          clientSecret = SecretValue("unused"),
          publicBaseUrl = "http://localhost:8080",
          groupsClaim = "groups",
          adminGroup = "runline-admin",
          developerGroup = "runline-developer",
          session = settings,
      )
  private val logs = CapturedLogs()

  // These tests are about the idle and absolute time; checking with the provider is the business
  // of SessionRevalidationTest, and its time is far off here.
  private fun revalidator(telemetry: AuthTelemetry) =
      SessionRevalidator(
          store,
          NimbusOidcProvider(oidcSettings),
          SessionCipher(ByteArray(32)),
          oidcSettings,
          clock,
          telemetry,
      )

  private val sessions =
      AuthTelemetry(OpenTelemetry.noop()).let {
        SessionService(store, settings, clock, it, revalidator(it))
      }

  @AfterTest fun close() = logs.close()

  private fun signIn(id: String = "id-${System.nanoTime()}", name: String = "alice"): String {
    val now = clock.instant()
    store.signIn(
        SignInIdentity("https://auth.example.test/", "sub-$name", name, "Display $name"),
        NewSession(
            SessionIds.hash(id),
            Role.ADMIN,
            "csrf-$id",
            byteArrayOf(1),
            now,
            now + settings.absolute,
        ),
        now,
    )
    return id
  }

  @Test
  fun `a valid session says who, in which role, and its CSRF token`() {
    val id = signIn("id-1")

    assertEquals(SessionView("alice", Role.ADMIN, "Display alice", "csrf-id-1"), sessions.find(id))
  }

  @Test
  fun `no identifier, an empty one or an unknown one is no session`() {
    signIn("id-1")

    assertNull(sessions.find(null))
    assertNull(sessions.find(""))
    assertNull(sessions.find("not-an-identifier"))
  }

  @Test
  fun `a session stays valid by use and ends when it was not used for the idle time`() {
    val id = signIn()

    clock.advance(Duration.ofMinutes(50))
    assertNotNull(sessions.find(id))
    clock.advance(Duration.ofMinutes(50))
    assertNotNull(sessions.find(id))
    clock.advance(Duration.ofHours(1))

    assertNull(sessions.find(id))
  }

  @Test
  fun `a session that is used all the time still ends at the absolute time`() {
    val id = signIn()

    repeat(5) {
      clock.advance(Duration.ofMinutes(59))
      assertNotNull(sessions.find(id), "at ${(it + 1) * 59} minutes")
    }
    // 354 minutes: used every 59 minutes, but past the 300 the session may live.
    clock.advance(Duration.ofMinutes(59))

    assertNull(sessions.find(id))
  }

  @Test
  fun `signing out ends the session, and signing out again, or with nothing, does nothing`() {
    val id = signIn()

    sessions.end(id)
    assertNull(sessions.find(id))
    sessions.end(id)
    sessions.end(null)
    sessions.end("unknown")
  }

  @Test
  fun `signing out is logged with the name, and a second time not at all`() {
    val id = signIn(name = "carol")

    sessions.end(id)
    sessions.end(id)

    val lines = logs.lines.filter { it.contains("Signed out") }
    assertEquals(1, lines.size, "$lines")
    assertTrue(lines.single().contains("carol"))
    assertFalse(lines.single().contains(id))
  }

  @Test
  fun `signing out is traced every time, and counted when a session ended`() {
    val spans = InMemorySpanExporter.create()
    val metrics = InMemoryMetricReader.create()
    val otel =
        OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(spans))
                    .build()
            )
            .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metrics).build())
            .build()
    val traced =
        AuthTelemetry(otel).let { SessionService(store, settings, clock, it, revalidator(it)) }
    val id = signIn(name = "dina")

    traced.end(id)
    traced.end(id)

    val results =
        spans.finishedSpanItems
            .filter { it.name == "auth.logout" }
            .map { it.attributes.get(AuthTelemetry.RESULT) }
    assertEquals(listOf("signed_out", "no_session"), results)
    val logouts =
        metrics.collectAllMetrics().single { it.name == "runline.auth.logouts" }.longSumData
    assertEquals(1L, logouts.points.sumOf { it.value })
    assertFalse(spans.finishedSpanItems.any { it.toString().contains(id) })
  }

  private fun lastUsedWritten(): java.time.Instant =
      dev.lawlan.runline.engine.support.PostgresTestContainer.connect(database).use { c ->
        c.createStatement().use { st ->
          st.executeQuery("SELECT max(last_used_at) FROM user_session").use {
            it.next()
            it.getObject(1, java.time.OffsetDateTime::class.java).toInstant()
          }
        }
      }

  @Test
  fun `the requests of a page do not each write the time of use`() {
    val id = signIn()
    val start = lastUsedWritten()

    repeat(10) {
      clock.advance(Duration.ofSeconds(3))
      assertNotNull(sessions.find(id))
    }
    assertEquals(start, lastUsedWritten(), "ten requests in thirty seconds wrote the time")

    clock.advance(Duration.ofSeconds(60))
    assertNotNull(sessions.find(id))
    assertEquals(
        clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS),
        lastUsedWritten(),
    )
  }
}
