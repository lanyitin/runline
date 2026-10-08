package dev.lawlan.runline.accessors.jdbc

import dev.lawlan.runline.accessors.BoundResources
import dev.lawlan.runline.accessors.Invalidation
import dev.lawlan.runline.accessors.tls.ClientCertificate
import dev.lawlan.runline.accessors.tls.ResourceTls
import dev.lawlan.runline.accessors.tls.TestPki
import dev.lawlan.runline.accessors.tls.TlsFailure
import dev.lawlan.runline.core.ResourceFailure
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * A `jdbc-pool` resource over TLS (WI-52) against a real PostgreSQL that speaks TLS and requires a
 * client certificate of some accounts: the pool's own context reaches the driver through the
 * Engine's socket factory, the host is checked, nothing is written to a file, and a failure of TLS
 * is told by its category.
 */
class JdbcTlsTest {
  private val profiles = JdbcProfiles(listOf(PostgresProfile))
  private val pools = JdbcPools(profiles)
  private val hosts = mutableListOf<BoundResources>()
  private val tlsFailures = CopyOnWriteArrayList<TlsFailure>()
  private val observer =
      object : JdbcObserver {
        override fun tlsFailed(resource: String, failure: TlsFailure) {
          tlsFailures += failure
        }
      }

  @AfterTest
  fun stop() {
    hosts.forEach { runCatching { it.invalidateAll(Invalidation.RUN_ENDED) } }
    pools.close()
  }

  private fun settings(role: String, host: String = server.host, tls: String = ""): JdbcSettings {
    val json =
        """{"kind":"postgresql","host":"$host","port":${server.port},"database":"${server.database}","username":"$role"$tls}"""
    return (JdbcSettings.parse(Json.parseToJsonElement(json).jsonObject, profiles)
            as JdbcSettingsResult.Valid)
        .settings
  }

  private fun host(settings: JdbcSettings, password: String, tls: ResourceTls?): BoundResources {
    val binding = pools.bind("db", settings, JdbcCredential.Password(password), 1, observer, tls)
    return BoundResources(mapOf("db" to binding)).also { hosts += it }
  }

  private fun query(host: BoundResources, sql: String): Map<String, Any?> =
      host.call(
          mapOf(
              "resource" to "db",
              "operation" to "jdbc.query",
              "arguments" to
                  hashMapOf<String, Any?>("sql" to sql, "parameters" to ArrayList<Any?>()),
          )
      )

  private fun role(certificateRequired: Boolean = false): Pair<String, String> {
    val role = "r_" + UUID.randomUUID().toString().replace("-", "").take(12)
    val password = "pw-" + UUID.randomUUID()
    server.createRole(role, password, certificateRequired)
    return role to password
  }

  private val trusted = ResourceTls(listOf(authority.certificate), null)
  private val withClient = ResourceTls(listOf(authority.certificate), clientCertificate)

  @Test
  fun `trusting the database's authority, statements run over TLS`() {
    val (role, password) = role()
    val answer =
        query(
            host(settings(role), password, trusted),
            "SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()",
        )

    assertEquals(true, answer["ok"], "$answer")
    assertEquals(listOf(listOf(true)), (answer["value"] as Map<*, *>)["rows"])
  }

  @Test
  fun `a database reached by a name its certificate is not for is refused, and the host is told why`() {
    val (role, password) = role()
    val answer = query(host(settings(role, host = "127.0.0.1"), password, trusted), "SELECT 1")

    assertEquals(ResourceFailure.CONNECTION_FAILED.name, answer["failure"])
    assertEquals(listOf(TlsFailure.HOSTNAME_MISMATCH), tlsFailures)
  }

  @Test
  fun `the check tells trust, host and client certificate failures apart`() {
    val (role, password) = role()
    val (certRole, certPassword) = role(certificateRequired = true)
    fun check(settings: JdbcSettings, password: String, tls: ResourceTls?) =
        JdbcProbe.check(PostgresProfile, settings, JdbcCredential.Password(password), 10_000, tls)

    assertNull(check(settings(role), password, trusted))
    assertNull(check(settings(certRole), certPassword, withClient))
    assertEquals(
        JdbcProbe.Failure(ResourceFailure.CONNECTION_FAILED, TlsFailure.TRUST_FAILED),
        check(settings(role), password, ResourceTls(listOf(otherAuthority.certificate), null)),
    )
    assertEquals(
        JdbcProbe.Failure(ResourceFailure.CONNECTION_FAILED, TlsFailure.HOSTNAME_MISMATCH),
        check(settings(role, host = "127.0.0.1"), password, trusted),
    )
    assertEquals(
        TlsFailure.CLIENT_CERT_REJECTED,
        check(settings(certRole), certPassword, trusted)?.tls,
    )
    // A wrong password is still the account's refusal, not the certificate's.
    assertEquals(
        JdbcProbe.Failure(ResourceFailure.DENIED, null),
        check(settings(role), "not-the-password", trusted),
    )
  }

  @Test
  fun `an account that requires a client certificate runs statements with the resource's`() {
    val (role, password) = role(certificateRequired = true)
    val answer =
        query(
            host(settings(role), password, withClient),
            "SELECT client_dn FROM pg_stat_ssl WHERE pid = pg_backend_pid()",
        )

    assertEquals(true, answer["ok"], "$answer")
    assertEquals(listOf(listOf("/CN=runline-client")), (answer["value"] as Map<*, *>)["rows"])
  }

  @Test
  fun `without certificates the connection is made as before WI-52, by the driver's default`() {
    val (role, password) = role()

    assertEquals(true, query(host(settings(role), password, null), "SELECT 1")["ok"])
  }

  @Test
  fun `other certificates make a new generation, and the run holding the old one goes on with it`() {
    val (role, password) = role()
    val first = host(settings(role), password, trusted)
    assertEquals(true, query(first, "SELECT 1")["ok"])

    // A reload replaced the trusted certificates: the next run gets a pool of its own.
    val second =
        host(
            settings(role),
            password,
            ResourceTls(listOf(authority.certificate, otherAuthority.certificate), null),
        )
    assertEquals(true, query(second, "SELECT 1")["ok"])
    assertEquals(2, pools.activeConnections("db"))
    assertEquals(true, query(first, "SELECT 1")["ok"])

    first.invalidateAll(Invalidation.RUN_ENDED)
    assertEquals(1, pools.activeConnections("db"))
    assertEquals(1, pools.holders("db"))
  }

  @Test
  fun `the connection names no file of a certificate or a key and verifies the host in full`() {
    val properties = PostgresProfile.tlsProperties("some-pool")

    assertEquals("verify-full", properties["sslmode"])
    assertEquals(PostgresTlsSocketFactory::class.java.name, properties["sslfactory"])
    for (file in listOf("sslcert", "sslkey", "sslrootcert", "sslpassword", "sslpasswordcallback")) {
      assertEquals(null, properties[file], file)
    }
  }

  private companion object {
    val pki = TestPki()
    val authority = pki.authority("Database CA")
    val otherAuthority = pki.authority("Other CA")
    val clientIdentity = pki.issue(authority, "runline-client")
    val clientCertificate = ClientCertificate(clientIdentity.key, clientIdentity.chain)
    val server: TlsPostgres by lazy {
      TlsPostgres(
          pki,
          pki.issue(authority, "localhost", listOf("dns:localhost")),
          listOf(authority.certificate),
      )
    }
  }
}
