package dev.lawlan.runline.accessors.jdbc

import dev.lawlan.runline.accessors.ReadOnlyException
import dev.lawlan.runline.accessors.ReadOnlyFailure
import dev.lawlan.runline.accessors.ReadOnlyQuery
import dev.lawlan.runline.accessors.ReadOnlyRows
import dev.lawlan.runline.accessors.ReadOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail

/**
 * Reading a real PostgreSQL for a view (ADR-033, WI-1301). The account is the one the runs use and
 * may write everything, so what keeps a read from writing is the Engine's and nothing of the
 * database's privileges.
 */
class PostgresViewReadTest {
  private val rig = JdbcRig()

  @AfterTest fun close() = rig.close()

  private fun filled() {
    rig.db.execute("CREATE TABLE article (id integer PRIMARY KEY, title text, state text)")
    rig.db.execute(
        "INSERT INTO article VALUES (1, 'alpha', 'kept'), (2, 'beta', 'failed'), (3, 'gamma', 'kept')"
    )
    rig.db.execute("CREATE SEQUENCE ticket")
    rig.db.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO ${rig.role}")
    rig.db.execute("GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO ${rig.role}")
  }

  private fun query(
      sql: String,
      columns: List<String>,
      parameters: Map<String, Any?> = emptyMap(),
      order: List<ReadOrder> = emptyList(),
      limit: Int = 50,
      offset: Long = 0,
      extraSettings: String = "",
      credential: JdbcCredential = JdbcCredential.Password(rig.password),
  ) =
      JdbcViewRead(
          PostgresProfile,
          rig.settings(extraSettings),
          credential,
          ReadOnlyQuery(sql, parameters, columns, order, limit, offset),
      )

  private fun read(
      sql: String,
      columns: List<String>,
      parameters: Map<String, Any?> = emptyMap(),
      order: List<ReadOrder> = emptyList(),
      limit: Int = 50,
      offset: Long = 0,
      extraSettings: String = "",
  ): ReadOnlyRows = query(sql, columns, parameters, order, limit, offset, extraSettings).run()

  private fun failure(block: () -> Unit): ReadOnlyFailure {
    try {
      block()
    } catch (e: ReadOnlyException) {
      return e.failure
    }
    fail("the read did not fail")
  }

  private val titles = "SELECT string_agg(title, ',' ORDER BY id) FROM article"

  @Test
  fun `a read gives the columns asked for, in that order, ordered as declared`() {
    filled()

    val page =
        read(
            "SELECT id, title, state FROM article WHERE state = :state",
            columns = listOf("title", "id"),
            parameters = mapOf("state" to "kept"),
            order = listOf(ReadOrder("id", descending = true)),
        )

    assertEquals(listOf(listOf<Any?>("gamma", 3L), listOf("alpha", 1L)), page.rows)
    assertEquals(false, page.hasMore)
    assertEquals(0, rig.db.sessions(), "the connection of the read is closed")
  }

  @Test
  fun `pages follow one another without a row twice or missed, and say whether more follow`() {
    rig.db.execute("CREATE TABLE n AS SELECT * FROM (VALUES (3), (1), (5), (2), (4)) AS t(v)")
    rig.db.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO ${rig.role}")
    fun page(offset: Long) = read("SELECT v FROM n", listOf("v"), limit = 2, offset = offset)

    val pages = listOf(page(0), page(2), page(4))

    assertEquals(
        listOf(listOf(1L, 2L), listOf(3L, 4L), listOf(5L)),
        pages.map { p -> p.rows.map { it.single() } },
    )
    assertEquals(listOf(true, true, false), pages.map { it.hasMore })
  }

  @Test
  fun `a statement that writes is refused with its own failure, and the data is as it was`() {
    filled()
    val attempts =
        listOf(
            "INSERT INTO article VALUES (9, 'new', 'kept')",
            "UPDATE article SET title = 'changed'",
            "DELETE FROM article",
            "TRUNCATE article",
            "DROP TABLE article",
            "CREATE TABLE other (a integer)",
            "WITH gone AS (DELETE FROM article RETURNING title) SELECT title FROM gone",
            "SELECT nextval('ticket') AS title",
            "COPY article TO PROGRAM 'true'",
        )

    for (sql in attempts) {
      assertEquals(ReadOnlyFailure.QUERY_REJECTED, failure { read(sql, listOf("title")) }, sql)
    }

    assertEquals("alpha,beta,gamma", rig.db.scalar(titles))
    assertEquals("1", rig.db.scalar("SELECT last_value FROM ticket"))
  }

  @Test
  fun `more than one statement is refused, however the second is hidden, and the data is as it was`() {
    filled()
    val attempts =
        listOf(
            "SELECT title FROM article; DELETE FROM article",
            "SELECT title FROM article) ; DELETE FROM article WHERE (1 = 1",
            "SELECT title FROM article /* ; */ ; DELETE FROM article",
            "SELECT title FROM article;\nDROP TABLE article",
            "SELECT 'it''s' AS title; DELETE FROM article",
            "SELECT E'\\'' AS title ; DELETE FROM article; --'",
            "SELECT 1 AS title; COMMIT; BEGIN READ WRITE; DELETE FROM article",
        )

    for (sql in attempts) {
      assertEquals(ReadOnlyFailure.QUERY_REJECTED, failure { read(sql, listOf("title")) }, sql)
    }

    assertEquals("alpha,beta,gamma", rig.db.scalar(titles))
  }

  @Test
  fun `a statement that changes the connection or makes it writable again is refused`() {
    filled()
    val attempts =
        listOf(
            "SET default_transaction_read_only = off",
            "SET default_transaction_read_only = off; DELETE FROM article",
            "SET TRANSACTION READ WRITE",
            "BEGIN READ WRITE",
            "COMMIT",
            "ROLLBACK",
            "RESET ALL",
            "SET ROLE postgres",
            "SELECT set_config('transaction_read_only', 'off', false) AS title",
            "SELECT set_config('default_transaction_read_only', 'off', false); DELETE FROM article",
        )

    for (sql in attempts) {
      assertEquals(ReadOnlyFailure.QUERY_REJECTED, failure { read(sql, listOf("title")) }, sql)
    }

    // A function may set a setting of the session; the session is the read's, and ends with it.
    val setting =
        read(
            "SELECT set_config('default_transaction_read_only', 'off', false) AS title",
            listOf("title"),
        )
    assertEquals(listOf(listOf<Any?>("off")), setting.rows)
    assertEquals(
        listOf(listOf<Any?>("on")),
        read("SELECT current_setting('default_transaction_read_only') AS title", listOf("title"))
            .rows,
    )
    assertEquals(
        ReadOnlyFailure.QUERY_REJECTED,
        failure { read("DELETE FROM article", listOf("title")) },
    )
    assertEquals("alpha,beta,gamma", rig.db.scalar(titles))
  }

  @Test
  fun `a connection opened to read refuses a change even when one reaches it`() {
    filled()

    JdbcConnector.open(
            PostgresProfile,
            rig.settings(),
            rig.password,
            reading = true,
        )
        .use { connection ->
          assertFailsWith<java.sql.SQLException> {
            connection.createStatement().use { it.executeUpdate("DELETE FROM article") }
          }
          connection.rollback()
          // Not even after the transaction of the read is over and another begins.
          assertFailsWith<java.sql.SQLException> {
            connection.createStatement().use { it.executeUpdate("DELETE FROM article") }
          }
        }

    assertEquals("alpha,beta,gamma", rig.db.scalar(titles))
  }

  @Test
  fun `a query that names what the database does not have is a mismatch of structure`() {
    filled()

    assertEquals(
        ReadOnlyFailure.SCHEMA_MISMATCH,
        failure { read("SELECT title FROM no_such_table", listOf("title")) },
    )
    assertEquals(
        ReadOnlyFailure.SCHEMA_MISMATCH,
        failure { read("SELECT title, no_such_column FROM article", listOf("title")) },
    )
    assertEquals(
        ReadOnlyFailure.SCHEMA_MISMATCH,
        failure { read("SELECT title FROM article", listOf("title", "declared_only")) },
    )
  }

  @Test
  fun `a page larger than the rows the resource allows, or than its bytes, is too large`() {
    rig.db.execute("CREATE TABLE t (a text)")
    rig.db.execute(
        "INSERT INTO t VALUES ('${"x".repeat(60)}'), ('${"y".repeat(60)}'), ('${"z".repeat(60)}')"
    )
    rig.db.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO ${rig.role}")

    assertEquals(
        ReadOnlyFailure.RESULT_TOO_LARGE,
        failure {
          read("SELECT a FROM t", listOf("a"), limit = 3, extraSettings = "\"maxRows\":2")
        },
    )
    assertEquals(
        ReadOnlyFailure.RESULT_TOO_LARGE,
        failure {
          read(
              "SELECT a FROM t",
              listOf("a"),
              limit = 3,
              extraSettings = "\"maxResponseBytes\":100",
          )
        },
    )
    read("SELECT a FROM t", listOf("a"), limit = 2, extraSettings = "\"maxRows\":2")
  }

  @Test
  fun `a value of a parameter is only ever a value, wherever the name is used`() {
    filled()
    val lookalikes =
        listOf("x' OR 1 = 1 --", "kept'; DELETE FROM article; --", ":state", "?", "\$1")

    val none = lookalikes.map {
      read(
          "SELECT title FROM article WHERE state = :state",
          listOf("title"),
          mapOf("state" to it),
      )
    }
    val number =
        read("SELECT title FROM article WHERE id = :id", listOf("title"), mapOf("id" to "2"))
    val twice =
        read(
            "SELECT title FROM article WHERE :state::text IS NULL OR state = :state ORDER BY id",
            listOf("title"),
            mapOf("state" to "kept"),
        )
    val absent =
        read(
            "SELECT title FROM article WHERE :state::text IS NULL OR state = :state",
            listOf("title"),
            mapOf("state" to null),
        )
    val inText =
        read(
            "SELECT ':state' AS title, \"x:y\", \$\$ :state \$\$ /* :state */ " +
                "FROM (SELECT 1 AS \"x:y\") AS s WHERE '10:30'::time < :t::time",
            listOf("title"),
            mapOf("t" to "11:00"),
        )

    none.forEach { assertEquals(emptyList(), it.rows) }
    assertEquals(listOf(listOf<Any?>("beta")), number.rows)
    assertEquals(listOf(listOf<Any?>("alpha"), listOf("gamma")), twice.rows)
    assertEquals(3, absent.rows.size)
    assertEquals(listOf(listOf<Any?>(":state")), inText.rows)
    assertEquals("3", rig.db.scalar("SELECT count(*) FROM article"))
  }

  @Test
  fun `a query that a name cannot be bound for, or that has a positional marker, is rejected`() {
    filled()
    val queries =
        listOf(
            "SELECT title FROM article WHERE state = :state" to emptyMap<String, Any?>(),
            "SELECT title FROM article WHERE state = ?" to mapOf("state" to "kept"),
            "SELECT title FROM article WHERE state = :state AND id > ?" to mapOf("state" to "kept"),
            "SELECT title FROM article WHERE id = \$1" to mapOf("state" to "kept"),
        )
    for ((sql, parameters) in queries) {
      assertEquals(
          ReadOnlyFailure.QUERY_REJECTED,
          failure { read(sql, listOf("title"), parameters) },
          sql,
      )
    }
  }

  @Test
  fun `a read that runs away stops when it is cancelled, and nothing of it is left connected`() {
    val read = query("SELECT 'x' AS slept FROM pg_sleep(60)", listOf("slept"))
    val pool = Executors.newSingleThreadExecutor()
    try {
      val running = pool.submit<ReadOnlyFailure> { failure { read.run() } }
      val deadline = System.nanoTime() + 10_000_000_000
      while (rig.db.sessions() == 0 && System.nanoTime() < deadline) Thread.sleep(20)

      read.cancel()

      assertEquals(ReadOnlyFailure.CANCELLED, running.get(10, TimeUnit.SECONDS))
      val gone = System.nanoTime() + 10_000_000_000
      while (rig.db.sessions() > 0 && System.nanoTime() < gone) Thread.sleep(20)
      assertEquals(0, rig.db.sessions())
    } finally {
      pool.shutdownNow()
    }
  }

  @Test
  fun `a password that is not accepted, one that cannot be had, and no database are each told`() {
    assertEquals(
        ReadOnlyFailure.CONNECTION_FAILED,
        failure {
          query("SELECT 1 AS one", listOf("one"), credential = JdbcCredential.Password("nope"))
              .run()
        },
    )
    assertEquals(
        ReadOnlyFailure.UNAVAILABLE,
        failure {
          query("SELECT 1 AS one", listOf("one"), credential = JdbcCredential.Unavailable).run()
        },
    )
    val closed = java.net.ServerSocket(0).use { it.localPort }
    assertEquals(
        ReadOnlyFailure.CONNECTION_FAILED,
        failure {
          JdbcViewRead(
                  PostgresProfile,
                  rig.settings(port = closed),
                  JdbcCredential.Password(rig.password),
                  ReadOnlyQuery("SELECT 1 AS one", emptyMap(), listOf("one"), emptyList(), 50, 0),
              )
              .run()
        },
    )
  }

  @Test
  fun `a read does not take a connection of the runs, even when they hold every one`() {
    filled()
    val run = rig.host(capacity = 1)
    rig.value(run, "jdbc.begin")
    rig.update(run, "INSERT INTO article VALUES (10, 'held', 'kept')")

    val page = read("SELECT title FROM article", listOf("title"))

    assertEquals(3, page.rows.size, "the uncommitted row of the run is not seen")
    assertEquals(1, rig.pools.activeConnections("db"))
    rig.value(run, "jdbc.commit")
  }

  @Test
  fun `a string is the text the check read, whatever the account is set to do with a backslash`() {
    filled()
    rig.db.execute("ALTER ROLE ${rig.role} SET standard_conforming_strings = off")

    val page = read("SELECT '\\' AS title", listOf("title"))
    val hidden = read("SELECT '\\'' ; DELETE FROM article; --' AS title", listOf("title"))

    assertEquals(listOf(listOf<Any?>("\\")), page.rows)
    assertEquals(1, hidden.rows.size)
    assertEquals("alpha,beta,gamma", rig.db.scalar(titles))
  }
}
