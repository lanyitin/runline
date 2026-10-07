package dev.lawlan.runline.accessors.jdbc

import dev.lawlan.runline.accessors.Invalidation
import dev.lawlan.runline.accessors.ResourceObserver
import dev.lawlan.runline.accessors.fake.BlackHole
import dev.lawlan.runline.core.ResourceFailure
import java.net.ServerSocket
import java.sql.SQLException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a run is told when something goes wrong with the database, and what is not told (WI-48): a
 * category and the SQLState, never the text the database or the driver wrote, which can hold the
 * address, the account, a statement or a value; and what the host's log gets instead, under an
 * errorId that is in the answer. The password is in neither.
 */
class JdbcErrorsTest {
  private val rig = JdbcRig()

  @AfterTest fun close() = rig.close()

  /** What the observer of the host was told about failures: the cause as the log would print it. */
  private class Logged : ResourceObserver {
    class Entry(val failure: ResourceFailure, val errorId: String?, val cause: Throwable?) {
      /** Everything a log would print of the cause: its messages and its chain. */
      val text: String
        get() =
            generateSequence(cause) { it.cause }
                .joinToString("\n") { "${it.javaClass.name}: ${it.message}" }
    }

    val entries = CopyOnWriteArrayList<Entry>()

    override fun failed(
        resource: String,
        type: String,
        operation: String,
        failure: ResourceFailure,
        errorId: String?,
        cause: Throwable?,
    ) {
      entries += Entry(failure, errorId, cause)
    }
  }

  private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

  @Test
  fun `a password the database does not accept is denied, and the answer holds the category and the state only`() {
    val log = Logged()
    val host = rig.host(password = "not-the-password-0xBAD", resourceObserver = log)

    val e = assertFailsWith<Failed> { rig.query(host, "SELECT 1") }

    assertEquals(ResourceFailure.DENIED, e.failure)
    assertEquals("28P01", e.sqlState)
    assertEquals(setOf("ok", "failure", "sqlState", "errorId"), e.answer.keys)
    val text = e.answer.toString()
    assertFalse(text.contains("0xBAD"), text)
    assertFalse(text.contains(rig.role), text)
    assertFalse(text.contains(rig.db.name), text)
    assertFalse(text.contains(RealPostgres.host), text)
  }

  @Test
  fun `a database nobody listens at is a failure to connect`() {
    val host = rig.host(port = closedPort())

    val e = assertFailsWith<Failed> { rig.query(host, "SELECT 1") }

    assertEquals(ResourceFailure.CONNECTION_FAILED, e.failure)
    assertNull(e.sqlState.takeIf { it != null && it.startsWith("08").not() })
    assertNotNull(e.errorId)
    assertFalse(e.answer.toString().contains("refused"), e.answer.toString())
    assertEquals(listOf(ResourceFailure.CONNECTION_FAILED), rig.observer.acquireFailures)
  }

  @Test
  fun `a database that does not answer is a connect timeout, within the limit`() {
    BlackHole.baseUrl()
    val host = rig.host(extra = """"timeouts":{"connectMs":700}""", hostName = BlackHole.HOST)

    val started = System.nanoTime()
    val e = assertFailsWith<Failed> { rig.query(host, "SELECT 1") }
    val tookMillis = (System.nanoTime() - started) / 1_000_000

    assertEquals(ResourceFailure.CONNECT_TIMEOUT, e.failure)
    assertTrue(tookMillis < 8_000, "took $tookMillis ms")
  }

  @Test
  fun `a refusal of the database is a SQL error with its SQLState and nothing of its text`() {
    val log = Logged()
    val host = rig.host(resourceObserver = log)
    rig.update(host, "CREATE TABLE people (email text PRIMARY KEY)")
    rig.update(host, "INSERT INTO people VALUES (?)", listOf("carol-private@example.org"))

    val e =
        assertFailsWith<Failed> {
          rig.update(host, "INSERT INTO people VALUES (?)", listOf("carol-private@example.org"))
        }

    assertEquals(ResourceFailure.SQL_ERROR, e.failure)
    assertEquals("23505", e.sqlState)
    assertNotNull(e.errorId)
    val said = e.answer.toString()
    assertFalse(said.contains("carol"), said)
    assertFalse(said.contains("people"), said)
    assertFalse(said.contains("INSERT"), said)
    // The log has the failure under the same errorId, but not the statement, a value or a name that
    // the database echoed: SQL and parameters are not logged (WI-48).
    val entry = log.entries.single { it.errorId == e.errorId }
    assertEquals(ResourceFailure.SQL_ERROR, entry.failure)
    assertTrue(entry.text.contains("23505"), entry.text)
    assertFalse(entry.text.contains("carol"), entry.text)
    assertFalse(entry.text.contains("INSERT"), entry.text)
    assertFalse(entry.text.contains("people"), entry.text)
  }

  @Test
  fun `a name the database echoes in its refusal is not in the log`() {
    val log = Logged()
    val host = rig.host(resourceObserver = log)

    val e = assertFailsWith<Failed> { rig.query(host, "SELECT 1 FROM no_such_table_zz") }

    assertEquals("42P01", e.sqlState)
    assertFalse(log.entries.single().text.contains("no_such_table_zz"), log.entries.single().text)
  }

  @Test
  fun `a password that is without the keystore is no connection at all`() {
    val host =
        rig.pools.bind("db", rig.settings(), JdbcCredential.Unavailable, 1, rig.observer).let {
          dev.lawlan.runline.accessors.BoundResources(mapOf("db" to it))
        }

    val e = assertFailsWith<Failed> { rig.query(host, "SELECT 1") }

    assertEquals(ResourceFailure.SECRET_UNAVAILABLE, e.failure)
    assertEquals(0, rig.db.sessions())
    assertEquals(listOf(ResourceFailure.SECRET_UNAVAILABLE), rig.observer.acquireFailures)
    host.invalidateAll(Invalidation.RUN_ENDED)
  }

  @Test
  fun `the cause for the log never holds the password, whatever a database or a driver wrote`() {
    val password = "pw-Zk93-very-secret"
    val driverSaid =
        SQLException(
            "FATAL: password authentication failed for user \"app\" (password was $password) " +
                "at jdbc:postgresql://db.internal:5432/app?password=$password",
            "28P01",
        )

    val cause = JdbcFailureCause.of(ResourceFailure.DENIED, driverSaid, password)

    val text = generateSequence<Throwable>(cause) { it.cause }.joinToString { it.message.orEmpty() }
    assertFalse(text.contains(password), text)
    assertTrue(text.contains("28P01"), text)
    // The driver's own words are kept for a failure that holds no statement, so it can be
    // diagnosed.
    assertTrue(text.contains("authentication failed"), text)
  }

  @Test
  fun `the cause for a SQL error has the state and the kind of exception and not the message`() {
    val driverSaid =
        SQLException(
            "ERROR: duplicate key value\n  Detail: Key (email)=(a@b) already exists.",
            "23505",
        )

    val cause = JdbcFailureCause.of(ResourceFailure.SQL_ERROR, driverSaid, "pw")

    val text = generateSequence<Throwable>(cause) { it.cause }.joinToString { it.message.orEmpty() }
    assertTrue(text.contains("23505"), text)
    assertFalse(text.contains("a@b"), text)
    assertFalse(text.contains("duplicate"), text)
    assertNull(cause.cause)
  }
}
