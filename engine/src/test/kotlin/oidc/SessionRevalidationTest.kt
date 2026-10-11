package dev.lawlan.runline.engine.oidc

import dev.lawlan.runline.engine.auth.Role
import dev.lawlan.runline.engine.config.OidcSettings
import dev.lawlan.runline.engine.config.SessionSettings
import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.secret.SecretValue
import dev.lawlan.runline.engine.support.CapturedLogs
import dev.lawlan.runline.engine.support.KeycloakTestProvider
import dev.lawlan.runline.engine.support.MutableClock
import dev.lawlan.runline.engine.support.PostgresTestContainer
import dev.lawlan.runline.engine.support.ProviderBrowser
import dev.lawlan.runline.engine.support.ProviderUser
import dev.lawlan.runline.engine.support.migratedDatabase
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import java.net.URI
import java.net.URLDecoder
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/**
 * A session is checked again with the provider while it is used (WI-1205, ADR-029 decision 9), with
 * a real PostgreSQL and a real Keycloak: what is found, what is done about it, what an outage does,
 * and how soon a change at the provider is felt. The people are changed through the provider's own
 * administration, and an outage is a provider that cannot be reached.
 */
class SessionRevalidationTest {
  private val database = migratedDatabase()
  private val dataSource = dataSourceOf(database)
  private val store = PostgresSessionStore(dataSource)
  private val clock = MutableClock()
  private val cipher = SessionCipher(ByteArray(32).also { SecureRandom().nextBytes(it) })
  private val logs = CapturedLogs()
  private val metrics = InMemoryMetricReader.create()
  private val otel =
      OpenTelemetrySdk.builder()
          .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metrics).build())
          .build()
  private val interval = Duration.ofSeconds(60)

  @AfterTest fun close() = logs.close()

  private fun settings(
      issuer: String = KeycloakTestProvider.issuer,
      revalidate: Duration = interval,
      grace: Duration = Duration.ofMinutes(15),
  ) =
      OidcSettings(
          issuer = issuer,
          clientId = KeycloakTestProvider.CLIENT_ID,
          clientSecret = SecretValue(KeycloakTestProvider.CLIENT_SECRET),
          publicBaseUrl = KeycloakTestProvider.PUBLIC_BASE_URL,
          groupsClaim = "groups",
          adminGroup = "runline-admin",
          developerGroup = "runline-developer",
          session =
              SessionSettings(
                  Duration.ofDays(1),
                  Duration.ofDays(7),
                  revalidate,
                  grace,
                  SecretValue("unused"),
              ),
      )

  /** One Engine process: its own provider address and settings; the database is shared. */
  private inner class Process(
      settings: OidcSettings = settings(),
      val clock: Clock = this@SessionRevalidationTest.clock,
  ) {
    val provider = NimbusOidcProvider(settings, clock)
    val telemetry = AuthTelemetry(otel)
    val login =
        LoginService(
            provider,
            PostgresLoginAttemptStore(dataSource),
            store,
            cipher,
            settings,
            emptySet(),
            clock,
            telemetry,
        )
    val sessions =
        SessionService(
            store,
            settings.session,
            clock,
            telemetry,
            SessionRevalidator(store, provider, cipher, settings, clock, telemetry),
        )

    /** The person signs in at the provider and is given a session; returns its identifier. */
    fun signIn(user: ProviderUser): String {
      val back = ProviderBrowser().signIn(login.start(null), user.username, user.password)
      val query =
          URI(back.toString()).rawQuery.split('&').associate {
            val (k, v) = it.split('=', limit = 2)
            k to URLDecoder.decode(v, Charsets.UTF_8)
          }
      return (login.complete(LoginCallback(query["code"], query["state"], null))
              as LoginOutcome.SignedIn)
          .sessionId
    }
  }

  private val live = Process()

  /** An Engine whose provider cannot be reached. */
  private fun cut(
      grace: Duration = Duration.ofMinutes(15),
      clock: Clock = this.clock,
  ) = Process(settings(issuer = "http://localhost:1/realms/runline", grace = grace), clock)

  private fun newUser(vararg groups: String) = KeycloakTestProvider.createUser(groups.toList())

  private fun counter(): Map<String, Long> =
      metrics
          .collectAllMetrics()
          .filter { it.name == "runline.auth.revalidations" }
          .flatMap { it.longSumData.points }
          .associate { point ->
            point.attributes
                .asMap()
                .entries
                .sortedBy { it.key.key }
                .joinToString(",") { "${it.key.key}=${it.value}" } to point.value
          }

  private fun session(id: String) =
      store.resume(SessionIds.hash(id), clock.instant(), Duration.ofDays(1))

  private fun rows(): Int =
      PostgresTestContainer.connect(database).use { c ->
        c.createStatement().use { s ->
          s.executeQuery("SELECT count(*) FROM user_session").use {
            it.next()
            it.getInt(1)
          }
        }
      }

  private fun validatedAt(id: String): Instant = session(id)!!.lastValidatedAt

  // ---- when it is done ----

  @Test
  fun `within the interval the provider is not asked, even a provider that cannot be reached`() {
    val user = newUser("runline-developer")
    val id = live.signIn(user)
    val signedAt = validatedAt(id)
    val gone = cut()

    clock.advance(interval - Duration.ofSeconds(1))

    assertNotNull(gone.sessions.find(id))
    assertEquals(emptyMap(), counter())
    assertEquals(signedAt, validatedAt(id))
  }

  @Test
  fun `after the interval the session is checked, and then left alone for another interval`() {
    val user = newUser("runline-developer")
    val id = live.signIn(user)

    clock.advance(interval)
    val view = assertNotNull(live.sessions.find(id))

    assertEquals(Role.DEVELOPER, view.role)
    assertEquals(mapOf("result=renewed" to 1L), counter())
    assertEquals(clock.instant(), validatedAt(id).let { clock.instant() })
    assertEquals(clock.instant().toEpochMilli() / 1000, validatedAt(id).toEpochMilli() / 1000)
    clock.advance(interval - Duration.ofSeconds(1))
    assertNotNull(live.sessions.find(id))
    assertEquals(mapOf("result=renewed" to 1L), counter())
  }

  @Test
  fun `a session that is not used is not checked, and is checked when it is used again`() {
    val user = newUser("runline-developer")
    val id = live.signIn(user)

    clock.advance(Duration.ofHours(5))
    assertEquals(emptyMap(), counter())

    assertNotNull(live.sessions.find(id))
    assertEquals(mapOf("result=renewed" to 1L), counter())
  }

  // ---- what is found ----

  @Test
  fun `a refresh token the provider rotates is kept, so the checks go on`() {
    val user = newUser("runline-developer")
    KeycloakTestProvider.rotateRefreshTokens(true)
    try {
      val id = live.signIn(user)
      val first = session(id)!!.refreshToken.toList()

      repeat(3) {
        clock.advance(interval)
        assertNotNull(live.sessions.find(id), "check ${it + 1}")
      }

      assertEquals(mapOf("result=renewed" to 3L), counter())
      assertNotEquals(first, session(id)!!.refreshToken.toList())
    } finally {
      KeycloakTestProvider.rotateRefreshTokens(false)
    }
  }

  @Test
  fun `a person promoted or demoted at the provider has the new role from the next check`() {
    val user = newUser("runline-developer")
    val id = live.signIn(user)
    assertEquals(Role.DEVELOPER, live.sessions.find(id)!!.role)

    KeycloakTestProvider.addToGroup(user, "runline-admin")
    clock.advance(interval)
    assertEquals(Role.ADMIN, live.sessions.find(id)!!.role)
    assertEquals(Role.ADMIN, session(id)!!.role)

    KeycloakTestProvider.removeFromGroup(user, "runline-admin")
    clock.advance(interval)
    assertEquals(Role.DEVELOPER, live.sessions.find(id)!!.role)
    assertEquals(mapOf("result=role_changed" to 2L), counter())
    assertTrue(
        logs.lines.any {
          it.contains("Role of") && it.contains(user.username) && it.contains("ADMIN")
        }
    )
  }

  @Test
  fun `a person who is in no allowed group any more loses the session`() {
    val user = newUser("runline-developer")
    val id = live.signIn(user)
    KeycloakTestProvider.removeFromGroup(user, "runline-developer")
    clock.advance(interval)

    assertNull(live.sessions.find(id))

    assertNull(session(id))
    assertEquals(0, rows())
    assertEquals(mapOf("reason=not_authorized,result=revoked" to 1L), counter())
    assertTrue(logs.lines.any { it.contains("not_authorized") && it.contains(user.username) })
  }

  @Test
  fun `a person disabled at the provider loses the session`() {
    val user = newUser("runline-admin")
    val id = live.signIn(user)
    KeycloakTestProvider.disable(user)
    clock.advance(interval)

    assertNull(live.sessions.find(id))

    assertEquals(0, rows())
    assertEquals(mapOf("reason=rejected,result=revoked" to 1L), counter())
  }

  @Test
  fun `a session revoked at the provider is lost`() {
    val user = newUser("runline-developer")
    val id = live.signIn(user)
    KeycloakTestProvider.logout(user)
    clock.advance(interval)

    assertNull(live.sessions.find(id))

    assertEquals(0, rows())
    assertEquals(mapOf("reason=rejected,result=revoked" to 1L), counter())
  }

  @Test
  fun `a session without a refresh token it can use is lost at the first check`() {
    for (sealed in listOf(ByteArray(0), ByteArray(40) { it.toByte() })) {
      val id = "damaged-${sealed.size}"
      val now = clock.instant()
      store.signIn(
          SignInIdentity("https://auth.example.test/", "sub-$id", id, id),
          NewSession(
              SessionIds.hash(id),
              Role.ADMIN,
              "csrf",
              sealed,
              now,
              now + Duration.ofDays(7),
          ),
          now,
      )
    }
    clock.advance(interval)

    assertNull(live.sessions.find("damaged-0"))
    assertNull(live.sessions.find("damaged-40"))

    assertEquals(0, rows())
    assertEquals(mapOf("reason=no_refresh_token,result=revoked" to 2L), counter())
  }

  // ---- an outage ----

  @Test
  fun `while the provider cannot be reached the session lives for the grace time, tried once an interval`() {
    val user = newUser("runline-developer")
    val id = live.signIn(user)
    val gone = cut()

    clock.advance(interval)
    assertNotNull(gone.sessions.find(id))
    assertNotNull(gone.sessions.find(id))
    assertEquals(mapOf("result=grace" to 1L), counter(), "the second request did not try again")

    clock.advance(interval)
    assertNotNull(gone.sessions.find(id))
    assertEquals(mapOf("result=grace" to 2L), counter())
    assertEquals(1, rows())
  }

  @Test
  fun `after the grace time the session is lost, and the provider is not asked to be forgiving`() {
    val user = newUser("runline-developer")
    val id = live.signIn(user)
    val gone = cut(grace = Duration.ofMinutes(5))

    clock.advance(Duration.ofMinutes(4))
    assertNotNull(gone.sessions.find(id))
    clock.advance(Duration.ofMinutes(2))

    assertNull(gone.sessions.find(id))

    assertEquals(0, rows())
    assertEquals(
        mapOf("result=grace" to 1L, "result=grace_expired" to 1L),
        counter(),
    )
  }

  @Test
  fun `a grace time of zero is strict, the first failure ends the session`() {
    val user = newUser("runline-developer")
    val id = live.signIn(user)
    val strict = cut(grace = Duration.ZERO)

    clock.advance(interval)

    assertNull(strict.sessions.find(id))
    assertEquals(mapOf("result=grace_expired" to 1L), counter())
    assertEquals(0, rows())
  }

  @Test
  fun `when the provider is back the next check is a normal one`() {
    val user = newUser("runline-developer")
    val id = live.signIn(user)
    val gone = cut()
    clock.advance(interval)
    assertNotNull(gone.sessions.find(id))

    clock.advance(interval)
    assertNotNull(live.sessions.find(id))

    assertEquals(mapOf("result=grace" to 1L, "result=renewed" to 1L), counter())
    assertEquals(clock.instant().toEpochMilli() / 1000, validatedAt(id).toEpochMilli() / 1000)
  }

  // ---- at the same moment ----

  @Test
  fun `requests of one session at the same moment ask the provider once`() {
    val user = newUser("runline-developer")
    KeycloakTestProvider.rotateRefreshTokens(true)
    try {
      val id = live.signIn(user)
      clock.advance(interval)
      val callers = 8
      val pool = Executors.newFixedThreadPool(callers)
      try {
        val gate = CountDownLatch(1)
        val results =
            (1..callers).map {
              pool.submit<SessionView?> {
                gate.await()
                live.sessions.find(id)
              }
            }
        gate.countDown()
        val views = results.map { it.get(60, TimeUnit.SECONDS) }

        // With rotation a second use of the same token would have been refused and ended the
        // session.
        assertTrue(views.all { it != null }, "$views")
        assertEquals(mapOf("result=renewed" to 1L), counter())
        assertEquals(1, rows())
      } finally {
        pool.shutdownNow()
      }
    } finally {
      KeycloakTestProvider.rotateRefreshTokens(false)
    }
  }

  // ---- how soon ----

  /** The time from the change at the provider to the first request that is refused. */
  private fun feltAfter(change: (ProviderUser) -> Unit, groups: Array<String>): Long {
    val real = Clock.systemUTC()
    val tenSeconds = Duration.ofSeconds(10)
    val engine = Process(settings(revalidate = tenSeconds), real)
    val user = newUser(*groups)
    val id = engine.signIn(user)
    assertNotNull(engine.sessions.find(id))
    val changedAt = System.nanoTime()
    change(user)
    while (engine.sessions.find(id) != null) {
      assertTrue(
          Duration.ofNanos(System.nanoTime() - changedAt) < tenSeconds + Duration.ofSeconds(5),
          "the session was still valid after an interval and five seconds",
      )
      Thread.sleep(250)
    }
    val millis = Duration.ofNanos(System.nanoTime() - changedAt).toMillis()
    assertTrue(millis <= tenSeconds.toMillis() + 1000, "felt after $millis ms (interval 10000 ms)")
    return millis
  }

  @Test
  fun `a change at the provider is felt within one interval and one request`() {
    val removed =
        feltAfter(
            { KeycloakTestProvider.removeFromGroup(it, "runline-developer") },
            arrayOf("runline-developer"),
        )
    val disabled = feltAfter({ KeycloakTestProvider.disable(it) }, arrayOf("runline-developer"))
    val revoked = feltAfter({ KeycloakTestProvider.logout(it) }, arrayOf("runline-developer"))

    println(
        "REVALIDATION-EFFECT interval=10000ms removed-from-group=${removed}ms disabled=${disabled}ms revoked=${revoked}ms"
    )
  }

  // ---- what is not said ----

  @Test
  fun `no token or identifier of the checks is in a log line or a metric`() {
    val user = newUser("runline-developer")
    KeycloakTestProvider.rotateRefreshTokens(true)
    val id: String
    val tokens = mutableListOf<String>()
    try {
      id = live.signIn(user)
      tokens += cipher.open(session(id)!!.refreshToken, SessionIds.hash(id))!!
      clock.advance(interval)
      live.sessions.find(id)
      tokens += cipher.open(session(id)!!.refreshToken, SessionIds.hash(id))!!
      KeycloakTestProvider.logout(user)
      clock.advance(interval)
      live.sessions.find(id)
    } finally {
      KeycloakTestProvider.rotateRefreshTokens(false)
    }

    val everything = logs.lines + metrics.collectAllMetrics().map { it.toString() }
    for (secret in tokens + listOf(id, KeycloakTestProvider.CLIENT_SECRET)) {
      assertTrue(
          everything.none { it.contains(secret) },
          "a secret of ${secret.length} characters is recorded",
      )
    }
  }
}
