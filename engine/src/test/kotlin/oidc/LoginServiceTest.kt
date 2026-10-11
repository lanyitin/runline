package dev.lawlan.runline.engine.oidc

import ch.qos.logback.classic.Level
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
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import kotlin.test.*

/**
 * The sign-in from the start to the session, with a real PostgreSQL and a real Keycloak (WI-1200,
 * 05-ipc): who gets in with which role, who is turned away and why, what a name is, and what can
 * not be done twice.
 */
class LoginServiceTest {
  private val database = migratedDatabase()
  private val dataSource = dataSourceOf(database)
  private val clock = MutableClock()
  private val sessionKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
  private val cipher = SessionCipher(sessionKey)
  private val logs = CapturedLogs()
  private val spans = InMemorySpanExporter.create()
  private val metrics = InMemoryMetricReader.create()
  private val otel =
      OpenTelemetrySdk.builder()
          .setTracerProvider(
              SdkTracerProvider.builder()
                  .addSpanProcessor(SimpleSpanProcessor.create(spans))
                  .build()
          )
          .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metrics).build())
          .build()
  private val sessionStore = PostgresSessionStore(dataSource)
  private val attemptStore = PostgresLoginAttemptStore(dataSource)

  private fun settings(
      groupsClaim: String = "groups",
      clientId: String = KeycloakTestProvider.CLIENT_ID,
  ) =
      OidcSettings(
          issuer = KeycloakTestProvider.issuer,
          clientId = clientId,
          clientSecret = SecretValue(KeycloakTestProvider.CLIENT_SECRET),
          publicBaseUrl = KeycloakTestProvider.PUBLIC_BASE_URL,
          groupsClaim = groupsClaim,
          adminGroup = "runline-admin",
          developerGroup = "runline-developer",
          session =
              SessionSettings(
                  Duration.ofHours(8),
                  Duration.ofDays(7),
                  Duration.ofSeconds(60),
                  Duration.ofMinutes(15),
                  SecretValue(Base64.getEncoder().encodeToString(sessionKey)),
              ),
      )

  private fun service(
      settings: OidcSettings = settings(),
      provider: OidcProvider = NimbusOidcProvider(settings),
      reserved: Set<String> = emptySet(),
  ) =
      LoginService(
          provider,
          attemptStore,
          sessionStore,
          cipher,
          settings,
          reserved,
          clock,
          AuthTelemetry(otel),
      )

  private val login = service()

  @AfterTest fun close() = logs.close()

  private fun query(uri: URI): Map<String, String> =
      uri.rawQuery.split('&').associate {
        val (k, v) = it.split('=', limit = 2)
        k to URLDecoder.decode(v, Charsets.UTF_8)
      }

  /** The person goes to the provider and signs in; returns what the provider sends back. */
  private fun comeBack(
      user: ProviderUser,
      service: LoginService = login,
      returnPath: String? = null,
  ): LoginCallback {
    val back = ProviderBrowser().signIn(service.start(returnPath), user.username, user.password)
    val parameters = query(back)
    return LoginCallback(parameters["code"], parameters["state"], parameters["error"])
  }

  private fun signedIn(outcome: LoginOutcome) = outcome as LoginOutcome.SignedIn

  private fun refused(outcome: LoginOutcome) = (outcome as LoginOutcome.Refused).error

  private fun hashOf(id: String) = MessageDigest.getInstance("SHA-256").digest(id.toByteArray())

  private fun resume(id: String) =
      sessionStore.resume(hashOf(id), clock.instant(), Duration.ofHours(8))

  private fun count(table: String): Int =
      PostgresTestContainer.connect(database).use { c ->
        c.createStatement().use { s ->
          s.executeQuery("SELECT count(*) FROM $table").use {
            it.next()
            it.getInt(1)
          }
        }
      }

  private fun counter(name: String): Map<String, Long> =
      metrics
          .collectAllMetrics()
          .filter { it.name == name }
          .flatMap { it.longSumData.points }
          .associate { point ->
            point.attributes
                .asMap()
                .entries
                .sortedBy { it.key.key }
                .joinToString(",") { "${it.key.key}=${it.value}" } to point.value
          }

  // ---- roles ----

  @Test
  fun `a member of the admin group signs in as an administrator under their name`() {
    val user = KeycloakTestProvider.createUser(listOf("runline-admin"))

    val outcome = signedIn(login.complete(comeBack(user)))

    val session = assertNotNull(resume(outcome.sessionId))
    assertEquals(Role.ADMIN, session.role)
    assertEquals(user.username, session.user.name)
    assertEquals(user.displayName, session.user.displayName)
    assertEquals("/", outcome.returnPath)
    assertEquals(clock.instant() + Duration.ofDays(7), outcome.expiresAt)
    assertTrue(session.csrfToken.length >= 32)
  }

  @Test
  fun `a member of the developer group signs in as a developer`() {
    val user = KeycloakTestProvider.createUser(listOf("runline-developer", "staff"))

    val outcome = signedIn(login.complete(comeBack(user)))

    assertEquals(Role.DEVELOPER, resume(outcome.sessionId)!!.role)
  }

  @Test
  fun `a member of both groups is an administrator`() {
    val user = KeycloakTestProvider.createUser(listOf("runline-developer", "runline-admin"))

    val outcome = signedIn(login.complete(comeBack(user)))

    assertEquals(Role.ADMIN, resume(outcome.sessionId)!!.role)
  }

  @Test
  fun `a person in neither group is refused and nothing is created`() {
    val user = KeycloakTestProvider.createUser(listOf("staff"))

    val outcome = login.complete(comeBack(user))

    assertEquals(LoginError.NOT_AUTHORIZED, refused(outcome))
    assertEquals(0, count("oidc_user"))
    assertEquals(0, count("user_session"))
  }

  @Test
  fun `a person with no groups claim at all is refused and nothing is created`() {
    val user = KeycloakTestProvider.createUser(listOf("runline-admin"))
    val noClaim = service(settings(groupsClaim = "no-such-claim"))

    val outcome = noClaim.complete(comeBack(user, noClaim))

    assertEquals(LoginError.NOT_AUTHORIZED, refused(outcome))
    assertEquals(0, count("oidc_user"))
    assertEquals(0, count("user_session"))
  }

  // ---- names ----

  @Test
  fun `a rename at the provider changes neither the name nor the person`() {
    val user = KeycloakTestProvider.createUser(listOf("runline-developer"))
    val first = signedIn(login.complete(comeBack(user)))
    val renamed = KeycloakTestProvider.rename(user, user.username + "-new", firstName = "Renamed")

    val second = signedIn(login.complete(comeBack(renamed)))

    val before = resume(first.sessionId)!!.user
    val after = resume(second.sessionId)!!.user
    assertEquals(before.id, after.id)
    assertEquals(user.username, after.name)
    assertEquals("Renamed ${user.username}", after.displayName)
    assertEquals(1, count("oidc_user"))
  }

  @Test
  fun `a name an API token has is refused and nothing is created`() {
    val user = KeycloakTestProvider.createUser(listOf("runline-developer"))
    val reserving = service(reserved = setOf(user.username))

    val outcome = reserving.complete(comeBack(user, reserving))

    assertEquals(LoginError.NAME_CONFLICT, refused(outcome))
    assertEquals(0, count("oidc_user"))
    assertEquals(0, count("user_session"))
  }

  @Test
  fun `a name another person already has is refused and nothing is created`() {
    val first = KeycloakTestProvider.createUser(listOf("runline-developer"))
    signedIn(login.complete(comeBack(first)))
    // At the provider the first person is renamed and a different person takes the name.
    KeycloakTestProvider.rename(first, first.username + "-moved")
    val second =
        KeycloakTestProvider.createUser(listOf("runline-developer"), username = first.username)

    val outcome = login.complete(comeBack(second))

    assertEquals(LoginError.NAME_CONFLICT, refused(outcome))
    assertEquals(1, count("oidc_user"))
    assertEquals(1, count("user_session"))
  }

  // ---- what cannot happen twice, and what takes too long ----

  @Test
  fun `the same return twice makes one session, the second is an unknown state`() {
    val user = KeycloakTestProvider.createUser(listOf("runline-developer"))
    val back = comeBack(user)

    signedIn(login.complete(back))
    val again = login.complete(back)

    assertEquals(LoginError.INVALID_STATE, refused(again))
    assertEquals(1, count("user_session"))
  }

  @Test
  fun `a code that was used cannot start another session, even with a state of a new sign-in`() {
    val user = KeycloakTestProvider.createUser(listOf("runline-developer"))
    val used = comeBack(user)
    signedIn(login.complete(used))
    // A second sign-in is started, and the code of the first is put with its state.
    val fresh = comeBack(user)

    val outcome = login.complete(LoginCallback(used.code, fresh.state, null))

    assertEquals(LoginError.INVALID_TOKEN, refused(outcome))
    assertEquals(1, count("user_session"))
  }

  @Test
  fun `a return after the time allowed is an unknown state`() {
    val user = KeycloakTestProvider.createUser(listOf("runline-developer"))
    val back = comeBack(user)
    clock.advance(LOGIN_ATTEMPT_TTL + Duration.ofSeconds(1))

    assertEquals(LoginError.INVALID_STATE, refused(login.complete(back)))
    assertEquals(0, count("user_session"))
    assertEquals(0, count("login_attempt"))
  }

  @Test
  fun `a return a moment before the time allowed still signs in`() {
    val user = KeycloakTestProvider.createUser(listOf("runline-developer"))
    val back = comeBack(user)
    clock.advance(LOGIN_ATTEMPT_TTL)

    signedIn(login.complete(back))
  }

  @Test
  fun `a state nobody started, or none, signs nobody in`() {
    assertEquals(
        LoginError.INVALID_STATE,
        refused(login.complete(LoginCallback("code", "not-a-state", null))),
    )
    assertEquals(
        LoginError.INVALID_STATE,
        refused(login.complete(LoginCallback("code", null, null))),
    )
  }

  @Test
  fun `a person who refuses at the provider comes back without a session, and the state is used up`() {
    login.start(null)
    val state = query(login.start(null))["state"]!!

    val outcome = login.complete(LoginCallback(null, state, "access_denied"))

    assertEquals(LoginError.ACCESS_DENIED, refused(outcome))
    assertEquals(LoginError.INVALID_STATE, refused(login.complete(LoginCallback("c", state, null))))
  }

  @Test
  fun `a return without a code is refused`() {
    val state = query(login.start(null))["state"]!!

    assertEquals(
        LoginError.INVALID_TOKEN,
        refused(login.complete(LoginCallback(null, state, null))),
    )
  }

  @Test
  fun `a provider that cannot be reached is a failure of its own, at the start and at the return`() {
    val gone = service(settings().copy(issuer = "http://localhost:1/realms/runline"))
    assertEquals(
        OidcFailure.Kind.UNAVAILABLE,
        assertFailsWith<OidcFailure> { gone.start(null) }.kind,
    )

    val state = query(login.start(null))["state"]!!
    val outcome = gone.complete(LoginCallback("code", state, null))

    assertEquals(LoginError.PROVIDER_UNAVAILABLE, refused(outcome))
    assertEquals(0, count("user_session"))
  }

  // ---- the way back ----

  @Test
  fun `only a path of this site is the way back`() {
    val user = KeycloakTestProvider.createUser(listOf("runline-developer"))

    assertEquals(
        "/runs/7?tab=log",
        signedIn(login.complete(comeBack(user, returnPath = "/runs/7?tab=log"))).returnPath,
    )
    for (bad in listOf("https://evil.example/", "//evil.example", "/\\evil.example")) {
      assertEquals("/", signedIn(login.complete(comeBack(user, returnPath = bad))).returnPath, bad)
    }
  }

  // ---- the session ----

  @Test
  fun `the refresh token is kept sealed with the session and the identifier only as a hash`() {
    val user = KeycloakTestProvider.createUser(listOf("runline-developer"))

    val outcome = signedIn(login.complete(comeBack(user)))

    val session = resume(outcome.sessionId)!!
    val sealed = assertNotNull(session.refreshToken)
    val token = assertNotNull(cipher.open(sealed, hashOf(outcome.sessionId)))
    assertTrue(token.isNotEmpty())
    assertFalse(String(sealed, Charsets.ISO_8859_1).contains(token))
    // The refresh token is sealed to this session: another session's context opens nothing.
    assertNull(cipher.open(sealed, hashOf("another")))
    assertTrue(outcome.sessionId.length >= 43, "an identifier of 256 bits in base64url")
  }

  @Test
  fun `identifiers do not repeat`() {
    val ids =
        (1..3).map {
          val user = KeycloakTestProvider.createUser(listOf("runline-developer"))
          signedIn(login.complete(comeBack(user))).sessionId
        }

    assertEquals(3, ids.toSet().size)
  }

  // ---- what is recorded, and what never is ----

  @Test
  fun `sign-ins and refusals are logged with names, roles and reasons and counted`() {
    val ok = KeycloakTestProvider.createUser(listOf("runline-admin"))
    val nobody = KeycloakTestProvider.createUser(listOf("staff"))
    val clash = KeycloakTestProvider.createUser(listOf("runline-developer"))
    val reserving = service(reserved = setOf(clash.username))

    reserving.complete(comeBack(ok, reserving))
    reserving.complete(comeBack(nobody, reserving))
    reserving.complete(comeBack(clash, reserving))
    reserving.complete(LoginCallback("c", "unknown", null))

    val lines = logs.lines.filter { it.contains("dev.lawlan.runline.engine.oidc") }
    assertTrue(
        lines.any { it.contains("INFO") && it.contains(ok.username) && it.contains("ADMIN") },
        "$lines",
    )
    assertTrue(
        lines.any { it.contains("not_authorized") && it.contains(nobody.username) },
        "$lines",
    )
    assertTrue(lines.any { it.contains("name_conflict") && it.contains(clash.username) }, "$lines")
    assertTrue(lines.any { it.contains("invalid_state") }, "$lines")
    assertEquals(mapOf("role=ADMIN" to 1L), counter("runline.auth.logins"))
    assertEquals(
        mapOf(
            "reason=not_authorized" to 1L,
            "reason=name_conflict" to 1L,
            "reason=invalid_state" to 1L,
        ),
        counter("runline.auth.logins.refused"),
    )
    assertEquals(mapOf("" to 1L), counter("runline.auth.name_conflicts"))
    val traced = spans.finishedSpanItems.filter { it.name == "auth.login.complete" }
    assertEquals(4, traced.size)
    assertEquals(
        setOf("signed_in", "not_authorized", "name_conflict", "invalid_state"),
        traced
            .map {
              it.attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("auth.result"))
            }
            .toSet(),
    )
  }

  @Test
  fun `no secret of the exchange is in a log line, a span or a metric`() {
    val user = KeycloakTestProvider.createUser(listOf("runline-admin"))
    val nobody = KeycloakTestProvider.createUser(listOf("staff"))
    val back = comeBack(user)
    val outcome = signedIn(login.complete(back))
    val refresh =
        cipher.open(resume(outcome.sessionId)!!.refreshToken!!, hashOf(outcome.sessionId))!!
    val denied = comeBack(nobody)
    login.complete(denied)
    login.complete(LoginCallback(back.code, back.state, null))

    val secrets =
        listOf(
            back.code!!,
            denied.code!!,
            back.state!!,
            outcome.sessionId,
            refresh,
            KeycloakTestProvider.CLIENT_SECRET,
            Base64.getEncoder().encodeToString(sessionKey),
        )
    val everything =
        logs.lines +
            spans.finishedSpanItems.map { it.toString() } +
            metrics.collectAllMetrics().map { it.toString() }
    for (secret in secrets) {
      assertTrue(
          everything.none { it.contains(secret) },
          "a secret of ${secret.length} characters is recorded",
      )
    }
    assertTrue(logs.at(Level.ERROR).isEmpty(), "${logs.at(Level.ERROR)}")
  }

  // ---- what makes a sign-in impossible to take back (WI-1207, ADR-030) ----

  @Test
  fun `a provider that gives no refresh token refuses the sign-in and nothing is created`() {
    val user = KeycloakTestProvider.createUser(listOf("runline-developer"))
    val noRefresh = service(settings(clientId = "runline-norefresh"))

    val outcome = noRefresh.complete(comeBack(user, noRefresh))

    assertEquals(LoginError.REFRESH_UNAVAILABLE, refused(outcome))
    assertEquals(0, count("oidc_user"))
    assertEquals(0, count("user_session"))
  }

  @Test
  fun `a person who is known is not touched by a sign-in that is refused for the missing refresh token`() {
    val user = KeycloakTestProvider.createUser(listOf("runline-developer"))
    signedIn(login.complete(comeBack(user)))
    val before = userRow()
    KeycloakTestProvider.rename(user, user.username, firstName = "Changed")
    clock.advance(Duration.ofHours(1))
    val noRefresh = service(settings(clientId = "runline-norefresh"))

    val outcome = noRefresh.complete(comeBack(user, noRefresh))

    assertEquals(LoginError.REFRESH_UNAVAILABLE, refused(outcome))
    assertEquals(before, userRow())
    assertEquals(1, count("user_session"))
  }

  @Test
  fun `the log says the refresh token is missing and where to look, and the refusal is counted`() {
    val user = KeycloakTestProvider.createUser(listOf("runline-developer"))
    val noRefresh = service(settings(clientId = "runline-norefresh"))
    val back = comeBack(user, noRefresh)

    noRefresh.complete(back)

    val line = logs.lines.single { it.contains("refresh_unavailable") }
    assertTrue(line.contains("no refresh token"), line)
    assertTrue(line.contains("offline_access"), line)
    assertEquals(mapOf("reason=refresh_unavailable" to 1L), counter("runline.auth.logins.refused"))
    assertTrue(logs.lines.none { it.contains(back.code!!) || it.contains(back.state!!) })
    assertEquals(
        "refresh_unavailable",
        spans.finishedSpanItems
            .single { it.name == "auth.login.complete" }
            .attributes
            .get(io.opentelemetry.api.common.AttributeKey.stringKey("auth.result")),
    )
  }

  @Test
  fun `an ID token signed with the client secret is refused, and the log names the algorithm`() {
    val user = KeycloakTestProvider.createUser(listOf("runline-developer"))
    val symmetric = service(settings(clientId = "runline-hs256"))
    val back = comeBack(user, symmetric)

    val outcome = symmetric.complete(back)

    assertEquals(LoginError.INVALID_TOKEN, refused(outcome))
    assertEquals(0, count("oidc_user"))
    assertEquals(0, count("user_session"))
    val line = logs.lines.single { it.contains("invalid_token") }
    assertTrue(line.contains("HS256") && line.contains("not accepted"), line)
    assertFalse(line.contains(KeycloakTestProvider.CLIENT_SECRET) || line.contains(back.code!!))
  }

  private fun userRow(): List<String> =
      PostgresTestContainer.connect(database).use { c ->
        c.createStatement().use { s ->
          s.executeQuery("SELECT display_name, last_login_at::text FROM oidc_user").use {
            it.next()
            listOf(it.getString(1), it.getString(2))
          }
        }
      }

  // ---- the room for sign-ins under way (WI-1201, ADR-030) ----

  private fun serviceWithRoom(room: Int) = service(settings().copy(maxPendingLogins = room))

  @Test
  fun `a sign-in beyond the room is refused with the time to wait, and writes nothing`() {
    val small = serviceWithRoom(2)
    small.start(null)
    clock.advance(Duration.ofSeconds(100))
    small.start(null)

    val e = assertFailsWith<LoginCapacityException> { small.start(null) }

    // The oldest of the two was made 100 seconds ago and is good for ten minutes.
    assertEquals(Duration.ofSeconds(500), e.retryAfter)
    assertEquals(2, count("login_attempt"))
  }

  @Test
  fun `a sign-in under way is completed though the room is full, and its room is free after`() {
    val small = serviceWithRoom(2)
    val user = KeycloakTestProvider.createUser(listOf("runline-developer"))
    val authorization = small.start(null)
    small.start(null)
    assertFailsWith<LoginCapacityException> { small.start(null) }

    val back = ProviderBrowser().signIn(authorization, user.username, user.password)
    val parameters = query(back)
    signedIn(small.complete(LoginCallback(parameters["code"], parameters["state"], null)))

    small.start(null)
    assertFailsWith<LoginCapacityException> { small.start(null) }
  }

  @Test
  fun `the room comes back as the sign-ins under way expire, with no clean-up`() {
    val small = serviceWithRoom(2)
    small.start(null)
    small.start(null)
    assertFailsWith<LoginCapacityException> { small.start(null) }

    clock.advance(LOGIN_ATTEMPT_TTL + Duration.ofSeconds(1))

    small.start(null)
    small.start(null)
    assertFailsWith<LoginCapacityException> { small.start(null) }
  }

  @Test
  fun `the sign-ins that fit in the room all succeed, also at the same moment`() {
    val room = 8
    val roomy = serviceWithRoom(room)
    val pool = java.util.concurrent.Executors.newFixedThreadPool(room + 4)
    try {
      val gate = java.util.concurrent.CountDownLatch(1)
      val results =
          (1..room + 4).map {
            pool.submit<Boolean> {
              gate.await()
              try {
                roomy.start(null)
                true
              } catch (e: LoginCapacityException) {
                false
              }
            }
          }
      gate.countDown()
      val outcomes = results.map { it.get(60, java.util.concurrent.TimeUnit.SECONDS) }

      assertEquals(room, outcomes.count { it })
      assertEquals(room, count("login_attempt"))
    } finally {
      pool.shutdownNow()
    }
  }

  // ---- disabled by an administrator (WI-1205) ----

  @Test
  fun `a person disabled here cannot sign in though the provider allows it, until enabled`() {
    val user = KeycloakTestProvider.createUser(listOf("runline-developer"))
    signedIn(login.complete(comeBack(user)))
    val admin = PostgresUserAdminStore(dataSource)
    admin.setDisabled(user.username, true, "root", clock.instant(), Duration.ofHours(8))

    val refused = login.complete(comeBack(user))

    assertEquals(LoginError.USER_DISABLED, refused(refused))
    assertEquals(0, count("user_session"))
    assertTrue(logs.lines.any { it.contains("user_disabled") && it.contains(user.username) })
    assertEquals(mapOf("reason=user_disabled" to 1L), counter("runline.auth.logins.refused"))

    admin.setDisabled(user.username, false, "root", clock.instant(), Duration.ofHours(8))
    signedIn(login.complete(comeBack(user)))
    assertEquals(1, count("user_session"))
  }
}
