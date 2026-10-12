package dev.lawlan.runline.engine.view

import dev.lawlan.runline.accessors.jdbc.RealPostgres
import dev.lawlan.runline.engine.auth.ApiIdentity
import dev.lawlan.runline.engine.auth.Role
import dev.lawlan.runline.engine.support.CapturedLogs
import dev.lawlan.runline.engine.support.Keystores
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.plugins.di.*
import io.ktor.server.testing.*
import java.net.ServerSocket
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

/**
 * Views over a real PostgreSQL resource, through the whole Engine (WI-1301): a keystore made by the
 * real `keytool`, an account that may write everything, a real PostgreSQL for the Engine and
 * another database of it for the pipeline's data.
 */
class ViewPostgresApiTest : ViewApiSupport() {
  private val keystores = Keystores()
  private val passwordFile = keystores.passwordFile()
  private val role = "view_" + UUID.randomUUID().toString().replace("-", "").take(12)
  private val password = "pw-" + UUID.randomUUID().toString().replace("-", "")
  private val keystore = keystores.pkcs12("views.p12", mapOf("db-pw" to password))
  private val target = RealPostgres.newDatabase()

  init {
    target.createRole(role, password, "GRANT ALL ON SCHEMA public TO $role")
    target.execute("CREATE TABLE article (id integer PRIMARY KEY, title text, state text)")
    target.execute(
        "INSERT INTO article VALUES (1, 'alpha', 'kept'), (2, 'beta', 'failed'), (3, 'gamma', 'kept')"
    )
    target.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO $role")
  }

  @AfterTest fun close() = target.close()

  private fun ApplicationTestBuilder.engineWithSecrets() =
      engine("secrets.keystorePath" to "$keystore", "secrets.passwordFile" to "$passwordFile")

  private fun settings(port: Int = RealPostgres.port) =
      """{"kind":"postgresql","host":"${RealPostgres.host}","port":$port,"database":"${target.name}","username":"$role"}"""

  private suspend fun ApplicationTestBuilder.definePostgres(port: Int = RealPostgres.port) =
      define(
          "feed-db",
          1,
          """"type":"jdbc-pool","settings":${settings(port)},"secretAlias":"db-pw"""",
      )

  private fun only(id: String, query: String) =
      view(
          id = id,
          query = query,
          parameters = "",
          columns = """@ViewColumn(name = "title", label = "Title")""",
          sort = "",
      )

  private fun titles() = target.scalar("SELECT string_agg(title, ',' ORDER BY id) FROM article")

  @Test
  fun `a view reads a PostgreSQL resource, and a query that writes is refused although the account may write`() =
      testApplication {
        engineWithSecrets()
        definePostgres()
        val hash =
            uploadFeed(
                view(),
                only("delete", "DELETE FROM article"),
                only("multi", "SELECT title FROM article; DELETE FROM article"),
                only("setting", "SET default_transaction_read_only = off; DELETE FROM article"),
                only("function", "SELECT nextval('nope') AS title"),
            )

        val read = viewRead(hash, query = "?param.state=kept").expect(HttpStatusCode.OK)

        assertEquals(listOf("alpha", "gamma"), read.rows().map { it[1].str() })
        for (id in listOf("delete", "multi", "setting")) {
          assertEquals("view_query_rejected", viewRead(hash, id).json().text("error"), id)
        }
        assertEquals("view_schema_mismatch", viewRead(hash, "function").json().text("error"))
        assertEquals("alpha,beta,gamma", titles())
        assertEquals(0, target.sessions(), "no connection of a read is left")
      }

  @Test
  fun `a database that cannot be reached is a failure of connection, not a 500`() =
      testApplication {
        engineWithSecrets()
        definePostgres(port = ServerSocket(0).use { it.localPort })
        val hash = uploadFeed()

        val response = viewRead(hash, query = "?param.state=kept")

        assertEquals(HttpStatusCode.BadGateway, response.status)
        assertEquals("view_connection_failed", response.json().text("error"))
      }

  @Test
  fun `a failure nobody expected is a 500 with an identifier, and the log holds neither the query nor the value`() {
    val logs = CapturedLogs()
    try {
      testApplication {
        engineWithSecrets()
        definePostgres()
        val marker = "QUERY-MARKER-55D1"
        val hash = uploadFeed(only("huge", "SELECT repeat('$marker', 2000000000) AS title"))

        val response = viewRead(hash, "huge")

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val body = response.json()
        assertEquals("internal_error", body.text("error"))
        val errorId = body.text("errorId")
        assertTrue(logs.lines.any { errorId in it && "Unexpected error" in it })
        assertTrue(logs.lines.none { marker in it }, "the query is in the log")
        assertFalse(marker in response.bodyAsText())
      }
    } finally {
      logs.close()
    }
  }

  @Test
  fun `when the request ends the query is cancelled in the database and its connection is gone`() =
      testApplication {
        engineWithSecrets()
        definePostgres()
        val hash = uploadFeed(only("sleep", "SELECT 'x' AS title FROM pg_sleep(120)"))
        startApplication()
        val reader = application.dependencies.resolve<ViewReader>()

        // The request of a client that has gone is a coroutine that is cancelled, here by a limit.
        val ended = runCatching {
          withTimeout(1_500) {
            reader.read(
                hash,
                null,
                "feed",
                "sleep",
                ViewRequest(emptyMap(), null, null),
                ApiIdentity("alice", Role.DEVELOPER),
            )
          }
        }

        assertTrue(ended.exceptionOrNull() is CancellationException, "$ended")
        val deadline = System.nanoTime() + 15_000_000_000
        while (target.sessions() > 0 && System.nanoTime() < deadline) delay(50)
        assertEquals(0, target.sessions(), "the query went on in the database")
      }

  private suspend fun awaitNoSessions() {
    val deadline = System.nanoTime() + 15_000_000_000
    while (target.sessions() > 0 && System.nanoTime() < deadline) delay(50)
  }

  @Test
  fun `a read ends after 30 seconds although the statement limit of the resource is far longer`() =
      testApplication {
        engineWithSecrets()
        definePostgres()
        val hash = uploadFeed(only("sleep", "SELECT 'x' AS title FROM pg_sleep(120)"))

        val started = System.nanoTime()
        val response = viewRead(hash, "sleep")
        val tookMillis = (System.nanoTime() - started) / 1_000_000

        assertEquals(HttpStatusCode.GatewayTimeout, response.status)
        assertEquals("view_timeout", response.json().text("error"))
        assertTrue(tookMillis in 29_000..40_000, "took $tookMillis ms")
        awaitNoSessions()
        assertEquals(0, target.sessions(), "the query went on in the database")
      }

  @Test
  fun `a statement limit of the resource under 30 seconds is the one that applies`() =
      testApplication {
        engineWithSecrets()
        define(
            "feed-db",
            1,
            """"type":"jdbc-pool","settings":${settings().removeSuffix("}")},"timeouts":{"statementMs":2000}},"secretAlias":"db-pw"""",
        )
        val hash = uploadFeed(only("sleep", "SELECT 'x' AS title FROM pg_sleep(120)"))

        val started = System.nanoTime()
        val response = viewRead(hash, "sleep")
        val tookMillis = (System.nanoTime() - started) / 1_000_000

        assertEquals("view_timeout", response.json().text("error"))
        assertTrue(tookMillis < 10_000, "took $tookMillis ms")
        awaitNoSessions()
        assertEquals(0, target.sessions(), "the query went on in the database")
      }
}
