package dev.lawlan.runline.accessors.jdbc

import dev.lawlan.runline.core.ResourceFailure
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The host's side of a `jdbc-pool` call is not trusted to be what the pipeline-facing accessor
 * would have made of it (WI-48): where the connection goes, with which account, through which
 * driver, are the resource's and nothing in a call changes them; and what a call may ask is checked
 * here, whatever the accessor in the run's class loader checked.
 */
class JdbcBoundaryTest {
  private val rig = JdbcRig()
  private val host by lazy { rig.host() }

  @AfterTest fun close() = rig.close()

  /** A server that counts who connects to it: where a hijacked connection would go. */
  private class Listener : AutoCloseable {
    private val server = ServerSocket(0)
    val connections = AtomicInteger()
    val port = server.localPort

    init {
      Thread.ofPlatform().daemon().start {
        try {
          while (true) {
            server.accept().close()
            connections.incrementAndGet()
          }
        } catch (e: java.io.IOException) {
          // closed
        }
      }
    }

    override fun close() = server.close()
  }

  @Test
  fun `nothing in a call chooses the address, the account, the password or the driver`() {
    Listener().use { evil ->
      val hostile =
          mapOf(
              "url" to "jdbc:postgresql://127.0.0.1:${evil.port}/x",
              "jdbcUrl" to "jdbc:postgresql://127.0.0.1:${evil.port}/x",
              "host" to "127.0.0.1",
              "port" to evil.port.toLong(),
              "database" to "postgres",
              "user" to "postgres",
              "username" to "postgres",
              "password" to "anything",
              "driver" to "org.evil.Driver",
              "driverClass" to "org.evil.Driver",
              "kind" to "oracle",
              "properties" to hashMapOf("socketFactory" to "org.evil.Factory"),
              "secretAlias" to "other-secret",
              "credential" to "x",
          )

      for ((key, value) in hostile) {
        for (operation in listOf("jdbc.query", "jdbc.update")) {
          val arguments = rig.statement("SELECT 1") + mapOf(key to value)

          val e =
              assertFailsWith<Failed>("$operation with $key") {
                rig.value(host, operation, arguments)
              }

          assertEquals(ResourceFailure.INVALID_ARGUMENT, e.failure, "$operation with $key")
        }
      }

      assertEquals(0, evil.connections.get(), "a connection went where the call asked")
      assertEquals(0, rig.db.sessions(), "a statement ran although the call was refused")
    }
  }

  @Test
  fun `a call that is not the shape of a statement is refused before a connection is made`() {
    val malformed =
        listOf<Map<String, Any?>>(
            emptyMap(),
            mapOf("sql" to null),
            mapOf("sql" to 5L),
            mapOf("sql" to listOf("SELECT 1")),
            mapOf("sql" to "SELECT 1", "parameters" to "x"),
            mapOf("sql" to "SELECT 1", "parameters" to hashMapOf("a" to 1)),
            mapOf("sql" to "SELECT 1", "parameters" to listOf(hashMapOf("a" to 1))),
            mapOf("sql" to "SELECT 1", "parameters" to listOf(listOf(1))),
            mapOf("sql" to "SELECT 1", "parameters" to listOf(Object())),
            mapOf("sql" to "SELECT 1", "parameters" to listOf(java.sql.Timestamp(0))),
            mapOf("sql" to "SELECT 1", "parameters" to listOf(java.sql.Connection::class.java)),
            mapOf("sql" to "SELECT 1", "parameters" to listOf(1)),
            mapOf("sql" to "SELECT 1", "parameters" to listOf(1.5f)),
            mapOf("sql" to "SELECT 1", "parameters" to listOf('c')),
        )

    for (arguments in malformed) {
      val e = assertFailsWith<Failed>("$arguments") { rig.value(host, "jdbc.query", arguments) }

      assertEquals(ResourceFailure.INVALID_ARGUMENT, e.failure, "$arguments")
    }
    assertEquals(0, rig.db.sessions())
  }

  @Test
  fun `a transaction operation takes no arguments`() {
    for (operation in listOf("jdbc.begin", "jdbc.commit", "jdbc.rollback")) {
      val e =
          assertFailsWith<Failed>(operation) {
            rig.value(host, operation, mapOf("sql" to "SELECT 1"))
          }

      assertEquals(ResourceFailure.INVALID_ARGUMENT, e.failure, operation)
    }
  }

  @Test
  fun `an operation that is not one of the type is refused, whichever type it belongs to`() {
    val operations =
        listOf(
            "jdbc.close",
            "jdbc.connect",
            "jdbc.metadata",
            "jdbc.QUERY",
            "jdbc.query ",
            "file.read",
            "file.write",
            "openai.call",
            "openai.stream.open",
            "",
        )
    for (operation in operations) {
      val e =
          assertFailsWith<Failed>("'$operation'") {
            rig.value(host, operation, rig.statement("SELECT 1"))
          }

      assertEquals(ResourceFailure.INVALID_ARGUMENT, e.failure, "'$operation'")
    }
    assertEquals(0, rig.db.sessions())
  }

  @Test
  fun `a request that is not even the shape of a call is an answer and not an exception`() {
    val requests =
        listOf<Map<String, Any?>>(
            mapOf("resource" to "db", "operation" to 5L, "arguments" to HashMap<String, Any?>()),
            mapOf("resource" to "db", "operation" to null, "arguments" to HashMap<String, Any?>()),
            mapOf("resource" to "db", "operation" to "jdbc.query", "arguments" to "SELECT 1"),
            mapOf("resource" to "db", "operation" to "jdbc.query", "arguments" to null),
            mapOf("resource" to "db", "operation" to "jdbc.query"),
            mapOf(
                "resource" to 5L,
                "operation" to "jdbc.query",
                "arguments" to HashMap<String, Any?>(),
            ),
            mapOf("operation" to "jdbc.query", "arguments" to HashMap<String, Any?>()),
        )

    for (request in requests) {
      val answer = host.call(request)

      assertEquals(false, answer["ok"], "$request")
    }
  }

  @Test
  fun `a resource the run does not hold is not provided, and another type's name does not open this one`() {
    val other = rig.call(host, "jdbc.query", rig.statement("SELECT 1"), resource = "other")
    val upper = rig.call(host, "jdbc.query", rig.statement("SELECT 1"), resource = "DB")

    for (answer in listOf(other, upper)) {
      assertEquals(false, answer["ok"])
      assertEquals(ResourceFailure.NOT_PROVIDED.name, answer["failure"])
    }
    assertEquals(0, rig.db.sessions())
  }

  @Test
  fun `what the account may do is what a run may do`() {
    val limited =
        JdbcRig(
            grants = {
              listOf(
                  "CREATE TABLE ledger (id int, amount int)",
                  "INSERT INTO ledger VALUES (1, 10)",
                  "GRANT USAGE ON SCHEMA public TO $it",
                  "GRANT SELECT ON ledger TO $it",
              )
            }
        )
    limited.use {
      val host = it.host()

      val read = it.query(host, "SELECT amount FROM ledger")
      val write = assertFailsWith<Failed> { it.update(host, "UPDATE ledger SET amount = 0") }
      val drop = assertFailsWith<Failed> { it.update(host, "DROP TABLE ledger") }
      val create = assertFailsWith<Failed> { it.update(host, "CREATE TABLE other (a int)") }
      val role = assertFailsWith<Failed> { it.update(host, "CREATE ROLE sneaky LOGIN") }

      assertEquals(10L, read.single()["amount"])
      assertEquals("42501", write.sqlState)
      assertEquals("42501", drop.sqlState)
      assertEquals(ResourceFailure.SQL_ERROR, drop.failure)
      assertEquals("42501", create.sqlState)
      assertEquals(ResourceFailure.SQL_ERROR, role.failure)
      assertEquals("10", it.db.scalar("SELECT amount FROM ledger"))
    }
  }
}
