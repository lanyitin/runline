package dev.lawlan.runline.accessors.jdbc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What an administrator may fix for a `jdbc-pool` resource (WI-48): the address is made of
 * structured fields by the database's profile and never given as a connection string, the extra
 * connection properties are those the profile allows and no others, and what is stored says every
 * effective value so that a newer Engine's defaults never change a resource that exists.
 */
class JdbcSettingsTest {
  private val profiles = JdbcProfiles(listOf(PostgresProfile))

  private fun parse(json: String, with: JdbcProfiles = profiles) =
      JdbcSettings.parse(Json.parseToJsonElement(json).jsonObject, with)

  private fun valid(json: String): JdbcSettings =
      assertIs<JdbcSettingsResult.Valid>(parse(json)).settings

  private fun problem(json: String): JdbcSettingsProblem =
      assertIs<JdbcSettingsResult.Invalid>(parse(json)).problem

  private val minimal =
      """{"kind":"postgresql","host":"db.internal","database":"app","username":"app_rw"}"""

  private fun with(extra: String) = minimal.dropLast(1) + "," + extra + "}"

  @Test
  fun `the kind, the host, the database and the account are enough, and the rest has its default`() {
    val s = valid(minimal)

    assertEquals("postgresql", s.kind)
    assertEquals("db.internal", s.host)
    assertEquals(5432, s.port)
    assertEquals("app", s.database)
    assertEquals("app_rw", s.username)
    assertEquals(1, s.connectionsPerRun)
    assertEquals(10_000L, s.connectTimeoutMillis)
    assertEquals(300_000L, s.statementTimeoutMillis)
    assertEquals(60_000L, s.quotaWaitMillis)
    assertEquals(10_000, s.maxRows)
    assertEquals(8L * 1024 * 1024, s.maxResponseBytes)
    assertEquals(emptyMap(), s.properties)
  }

  @Test
  fun `the normalised form says every effective value and parses back to the same settings`() {
    val first =
        assertIs<JdbcSettingsResult.Valid>(parse(with(""""port":6543,"connectionsPerRun":3""")))
    val written = first.normalized

    assertEquals("6543", written["port"]!!.jsonPrimitive.content)
    assertEquals("3", written["connectionsPerRun"]!!.jsonPrimitive.content)
    assertEquals("300000", written["timeouts"]!!.jsonObject["statementMs"]!!.jsonPrimitive.content)
    assertEquals("10000", written["maxRows"]!!.jsonPrimitive.content)
    val again = assertIs<JdbcSettingsResult.Valid>(JdbcSettings.parse(written, profiles))
    assertEquals(first.settings, again.settings)
    assertEquals(written, again.normalized)
  }

  @Test
  fun `a kind without a profile is refused as an unsupported database`() {
    assertEquals(
        JdbcSettingsProblem.UNSUPPORTED_DATABASE,
        problem("""{"kind":"oracle","host":"h","database":"d","username":"u"}"""),
    )
    assertEquals(
        JdbcSettingsProblem.UNSUPPORTED_DATABASE,
        problem(minimal.replace("postgresql", "PostgreSQL")),
    )
  }

  @Test
  fun `a member that is missing, unknown or of the wrong shape is invalid settings`() {
    val cases =
        listOf(
            """{}""",
            """{"host":"h","database":"d","username":"u"}""",
            """{"kind":"postgresql","database":"d","username":"u"}""",
            """{"kind":"postgresql","host":"h","username":"u"}""",
            """{"kind":"postgresql","host":"h","database":"d"}""",
            with(""""password":"x""""),
            with(""""url":"jdbc:postgresql://evil/db""""),
            with(""""driver":"org.evil.Driver""""),
            with(""""secretAlias":"x""""),
            with(""""port":"5432""""),
            with(""""port":0"""),
            with(""""port":65536"""),
            with(""""host":5"""),
            with(""""username":"""""),
            with(""""properties":["a"]"""),
        )

    for (case in cases) {
      assertEquals(JdbcSettingsProblem.INVALID_SETTINGS, problem(case), case)
    }
  }

  @Test
  fun `an address that could carry more than a host and a database is refused`() {
    val hosts =
        listOf(
            "db.internal/other",
            "db.internal?sslmode=disable",
            "user@db.internal",
            "db.internal:5432",
            "db1,db2",
            "jdbc:postgresql://evil/db",
            "db internal",
            "db.internal\n",
            "",
            "-db",
            "..",
        )
    for (host in hosts) {
      val json =
          """{"kind":"postgresql","host":${Json.encodeToString(kotlinx.serialization.serializer<String>(), host)},"database":"app","username":"u"}"""
      assertEquals(JdbcSettingsProblem.INVALID_SETTINGS, problem(json), "host '$host'")
    }
    val databases = listOf("app?ssl=false", "app/x", "app&x=1", "a b", "", "app;x")
    for (database in databases) {
      val json =
          """{"kind":"postgresql","host":"h","database":${Json.encodeToString(kotlinx.serialization.serializer<String>(), database)},"username":"u"}"""
      assertEquals(JdbcSettingsProblem.INVALID_SETTINGS, problem(json), "database '$database'")
    }
  }

  @Test
  fun `an address of a name, an IPv4 number or an IPv6 number in brackets is accepted`() {
    for (host in listOf("db", "db.internal", "10.0.0.5", "DB-1.example.org", "[::1]")) {
      assertEquals(host, valid(minimal.replace("db.internal", host)).host)
    }
  }

  @Test
  fun `only the properties the profile allows can be set, with the values it accepts`() {
    val s =
        valid(
            with(
                """"properties":{"ApplicationName":"nightly","currentSchema":"etl","tcpKeepAlive":"true"}"""
            )
        )

    assertEquals(
        mapOf("ApplicationName" to "nightly", "currentSchema" to "etl", "tcpKeepAlive" to "true"),
        s.properties,
    )
    assertEquals(
        JdbcSettingsProblem.INVALID_SETTINGS,
        problem(with(""""properties":{"tcpKeepAlive":"maybe"}""")),
    )
    assertEquals(
        JdbcSettingsProblem.INVALID_SETTINGS,
        problem(with(""""properties":{"ApplicationName":5}""")),
    )
  }

  @Test
  fun `a property that loads a class, writes a file, is a secret or weakens the connection is not allowed`() {
    val forbidden =
        listOf(
            "socketFactory",
            "socketFactoryArg",
            "sslfactory",
            "sslfactoryarg",
            "sslmode",
            "ssl",
            "sslrootcert",
            "sslcert",
            "sslkey",
            "sslpassword",
            "sslpasswordcallback",
            "sslhostnameverifier",
            "authenticationPluginClassName",
            "loggerLevel",
            "loggerFile",
            "password",
            "user",
            "passfile",
            "options",
            "targetServerType",
            "hostRecheckSeconds",
            "PGHOST",
            "assumeMinServerVersion",
            "gsslib",
            "jaasApplicationName",
            "kerberosServerName",
            "replication",
            "applicationname",
            // A statement can lift it again, so it would only look like a guard: the rights of the
            // account are the guard.
            "readOnly",
            "readOnlyMode",
            "defaultRowFetchSize",
            // Nothing of TLS either (WI-52): the profile fixes it, and the pool's own context is
            // found by a property of the Engine's that an administrator cannot set.
            "sslNegotiation",
            "sslResponseTimeout",
            "sslcertmode",
            PostgresProfile.TLS_CONTEXT_PROPERTY,
        )
    for (name in forbidden) {
      assertEquals(
          JdbcSettingsProblem.PROPERTY_NOT_ALLOWED,
          problem(with(""""properties":{"$name":"x"}""")),
          name,
      )
    }
  }

  @Test
  fun `the limits are whole numbers in range and a timeout that is not positive is refused`() {
    assertEquals(64, valid(with(""""connectionsPerRun":64""")).connectionsPerRun)
    for (bad in listOf("0", "65", "-1", "\"2\"")) {
      assertEquals(
          JdbcSettingsProblem.INVALID_LIMIT,
          problem(with(""""connectionsPerRun":$bad""")),
          bad,
      )
    }
    for (member in listOf("maxRows", "maxResponseBytes")) {
      assertEquals(JdbcSettingsProblem.INVALID_LIMIT, problem(with(""""$member":0""")), member)
    }
    val timeouts =
        valid(with(""""timeouts":{"connectMs":2000,"statementMs":90000,"quotaWaitMs":500}"""))
    assertEquals(
        Triple(2000L, 90_000L, 500L),
        Triple(
            timeouts.connectTimeoutMillis,
            timeouts.statementTimeoutMillis,
            timeouts.quotaWaitMillis,
        ),
    )
    for (bad in
        listOf(
            """{"connectMs":0}""",
            """{"statementMs":-5}""",
            """{"quotaWaitMs":"9"}""",
            """{"idleMs":5}""",
        )) {
      assertEquals(JdbcSettingsProblem.INVALID_TIMEOUT, problem(with(""""timeouts":$bad""")), bad)
    }
  }

  @Test
  fun `a second profile is chosen by the kind alone, with its own rules`() {
    val other =
        object : JdbcProfile by PostgresProfile {
          override val kind = "otherdb"
          override val defaultPort = 1521
          override val allowedProperties = mapOf("Flavor" to PropertyRule { it == "mild" })
        }
    val both = JdbcProfiles(listOf(PostgresProfile, other))
    val json =
        """{"kind":"otherdb","host":"h","database":"d","username":"u","properties":{"Flavor":"mild"}}"""

    val s = assertIs<JdbcSettingsResult.Valid>(parse(json, both)).settings

    assertEquals(1521, s.port)
    assertEquals(mapOf("Flavor" to "mild"), s.properties)
    // The same settings are not valid where that profile is not carried, nor with the other's
    // rules.
    assertEquals(
        JdbcSettingsProblem.UNSUPPORTED_DATABASE,
        assertIs<JdbcSettingsResult.Invalid>(parse(json)).problem,
    )
    assertEquals(
        JdbcSettingsProblem.PROPERTY_NOT_ALLOWED,
        problem(minimal.dropLast(1) + ""","properties":{"Flavor":"mild"}}"""),
    )
    assertNull(PostgresProfile.allowedProperties["Flavor"])
    assertTrue(both.kinds == setOf("postgresql", "otherdb"))
  }

  @Test
  fun `trusted certificates and a client certificate are named by alias, stored in lower case`() {
    val result =
        assertIs<JdbcSettingsResult.Valid>(
            parse(with(""""trustAliases":["DB-CA"],"clientCertAlias":"App-Client""""))
        )

    assertEquals(listOf("db-ca"), result.settings.tls.trustAliases)
    assertEquals("app-client", result.settings.tls.clientCertAlias)
    assertEquals(
        listOf("db-ca"),
        result.normalized["trustAliases"]!!
            .let { it as kotlinx.serialization.json.JsonArray }
            .map { it.jsonPrimitive.content },
    )
    assertEquals("app-client", result.normalized["clientCertAlias"]!!.jsonPrimitive.content)
    assertTrue("trustAliases" !in assertIs<JdbcSettingsResult.Valid>(parse(minimal)).normalized)
  }

  @Test
  fun `a certificate alias that is not written like one, or a member of the wrong shape, is refused`() {
    assertEquals(
        JdbcSettingsProblem.INVALID_ALIAS,
        problem(with(""""trustAliases":["has space"]""")),
    )
    assertEquals(JdbcSettingsProblem.INVALID_ALIAS, problem(with(""""clientCertAlias":"-x"""")))
    assertEquals(JdbcSettingsProblem.INVALID_SETTINGS, problem(with(""""trustAliases":"db-ca"""")))
  }
}
