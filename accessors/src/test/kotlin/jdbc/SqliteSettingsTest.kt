package dev.lawlan.runline.accessors.jdbc

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What an administrator may fix for a `jdbc-pool` resource of the kind `sqlite` (WI-1003): the file
 * of the database by a path below the resource root and the limits every kind has, and nothing that
 * would say a host, an account, a key or a connection string.
 */
class SqliteSettingsTest {
  private val profiles =
      JdbcProfiles(listOf(PostgresProfile, SqliteProfile(Files.createTempDirectory("sqlite-root"))))

  private fun parse(json: String) =
      JdbcSettings.parse(Json.parseToJsonElement(json).jsonObject, profiles)

  private fun valid(json: String): JdbcSettings =
      assertIs<JdbcSettingsResult.Valid>(parse(json)).settings

  private fun problem(json: String): JdbcSettingsProblem =
      assertIs<JdbcSettingsResult.Invalid>(parse(json)).problem

  private fun with(extra: String) = """{"kind":"sqlite","path":"digest.db",$extra}"""

  @Test
  fun `the kind and a path are enough, and the rest has the defaults every kind has`() {
    val s = valid("""{"kind":"sqlite","path":"runs/digest.db"}""")

    assertEquals("sqlite", s.kind)
    assertEquals(JdbcLocation.LocalFile("runs/digest.db"), s.location)
    assertEquals(1, s.connectionsPerRun)
    assertEquals(10_000L, s.connectTimeoutMillis)
    assertEquals(300_000L, s.statementTimeoutMillis)
    assertEquals(60_000L, s.quotaWaitMillis)
    assertEquals(10_000, s.maxRows)
    assertEquals(emptyMap(), s.properties)
    assertEquals(true, s.tls.isEmpty)
  }

  @Test
  fun `the normalised form says every effective value, with the path and without any host or account`() {
    val first = assertIs<JdbcSettingsResult.Valid>(parse(with(""""connectionsPerRun":3""")))
    val written = first.normalized

    assertEquals(
        listOf("kind", "path", "connectionsPerRun", "timeouts", "maxRows", "maxResponseBytes"),
        written.keys.toList(),
    )
    assertEquals("digest.db", written["path"]!!.jsonPrimitive.content)
    val again = assertIs<JdbcSettingsResult.Valid>(JdbcSettings.parse(written, profiles))
    assertEquals(first.settings, again.settings)
    assertEquals(written, again.normalized)
  }

  @Test
  fun `a path that leads out of the resource root, as it is written, is refused as such`() {
    for (path in listOf("/etc/runline.db", "../runline.db", "a/../../runline.db", "..")) {
      assertEquals(
          JdbcSettingsProblem.PATH_OUTSIDE_ROOT,
          problem("""{"kind":"sqlite","path":"$path"}"""),
          path,
      )
    }
  }

  @Test
  fun `a path that is not a plain relative file name is invalid settings`() {
    val paths =
        listOf(
            "",
            ".",
            "./digest.db",
            "a/./digest.db",
            "a/../digest.db",
            "a//digest.db",
            "a/",
            ".hidden.db",
            "a b.db",
            "digest.db?mode=ro",
            "file:digest.db",
            "a\\b.db",
            "digest.db#x",
            "a/b/c/d/e/f/g/h/i.db",
            "x".repeat(129),
        )
    for (path in paths) {
      assertEquals(
          JdbcSettingsProblem.INVALID_SETTINGS,
          problem("""{"kind":"sqlite","path":"$path"}"""),
          path,
      )
    }
    assertEquals(JdbcSettingsProblem.INVALID_SETTINGS, problem("""{"kind":"sqlite"}"""))
    assertEquals(JdbcSettingsProblem.INVALID_SETTINGS, problem("""{"kind":"sqlite","path":5}"""))
  }

  @Test
  fun `a member of a database reached over a network, a connection string or a certificate is invalid settings`() {
    val extras =
        listOf(
            """"host":"h"""",
            """"port":5432""",
            """"database":"d"""",
            """"username":"u"""",
            """"password":"x"""",
            """"url":"jdbc:sqlite:/etc/x"""",
            """"trustAliases":["ca"]""",
            """"clientCertAlias":"c"""",
            """"secretAlias":"k"""",
        )
    for (extra in extras) {
      assertEquals(JdbcSettingsProblem.INVALID_SETTINGS, problem(with(extra)), extra)
    }
  }

  @Test
  fun `no extra connection property is allowed, so the pragmas of the file are never the administrator's`() {
    for (name in
        listOf("journal_mode", "foreign_keys", "busy_timeout", "open_mode", "ApplicationName")) {
      assertEquals(
          JdbcSettingsProblem.PROPERTY_NOT_ALLOWED,
          problem(with(""""properties":{"$name":"x"}""")),
          name,
      )
    }
    assertEquals(
        emptyMap(),
        SqliteProfile(Files.createTempDirectory("sqlite-root")).allowedProperties,
    )
  }

  @Test
  fun `the timeouts and limits are those of every kind, and are refused the same way`() {
    val s =
        valid(
            with(
                """"connectionsPerRun":2,"maxRows":5,"timeouts":{"connectMs":1000,"statementMs":2000,"quotaWaitMs":3000}"""
            )
        )

    assertEquals(2, s.connectionsPerRun)
    assertEquals(5, s.maxRows)
    assertEquals(2000L, s.statementTimeoutMillis)
    assertEquals(
        JdbcSettingsProblem.INVALID_TIMEOUT,
        problem(with(""""timeouts":{"connectMs":0}""")),
    )
    assertEquals(JdbcSettingsProblem.INVALID_LIMIT, problem(with(""""connectionsPerRun":0""")))
  }

  @Test
  fun `a kind other than the two is unsupported, and the members of the two do not mix`() {
    assertEquals(
        JdbcSettingsProblem.UNSUPPORTED_DATABASE,
        problem("""{"kind":"sqlite3","path":"a.db"}"""),
    )
    assertEquals(
        JdbcSettingsProblem.INVALID_SETTINGS,
        problem("""{"kind":"postgresql","host":"h","database":"d","username":"u","path":"a.db"}"""),
    )
  }
}
