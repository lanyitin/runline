package dev.lawlan.runline.accessors.jdbc

import dev.lawlan.runline.accessors.ReadOnlyFailure
import dev.lawlan.runline.accessors.ReadOrder
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Reading a SQLite file for a view (ADR-033, WI-1301) against a real file, in the log mode the runs
 * use: what a read may do to the file, and what it must not do to the run that writes it.
 */
class SqliteViewReadTest {
  private val rig = SqliteViewRig()

  @AfterTest fun close() = rig.close()

  private fun filled() =
      rig.execute(
          "PRAGMA journal_mode = WAL",
          "CREATE TABLE article (id INTEGER PRIMARY KEY, title TEXT, state TEXT)",
          "INSERT INTO article VALUES (1, 'alpha', 'kept'), (2, 'beta', 'failed'), (3, 'gamma', 'kept')",
      )

  @Test
  fun `a read gives the columns asked for, in that order, ordered as declared`() {
    filled()

    val page =
        rig.read(
            "SELECT id, title, state FROM article WHERE state = :state",
            columns = listOf("title", "id"),
            parameters = mapOf("state" to "kept"),
            order = listOf(ReadOrder("id", descending = true)),
        )

    assertEquals(listOf(listOf<Any?>("gamma", 3L), listOf("alpha", 1L)), page.rows)
    assertEquals(false, page.hasMore)
  }

  @Test
  fun `pages follow one another without a row twice or missed, and say whether more follow`() {
    rig.execute(
        "PRAGMA journal_mode = WAL",
        "CREATE TABLE n (v INTEGER)",
        "INSERT INTO n VALUES (3), (1), (5), (2), (4)",
    )
    fun page(offset: Long) = rig.read("SELECT v FROM n", listOf("v"), limit = 2, offset = offset)

    val first = page(0)
    val second = page(2)
    val third = page(4)

    assertEquals(listOf(listOf<Any?>(1L), listOf(2L)), first.rows)
    assertEquals(listOf(listOf<Any?>(3L), listOf(4L)), second.rows)
    assertEquals(listOf(listOf<Any?>(5L)), third.rows)
    assertEquals(listOf(true, true, false), listOf(first, second, third).map { it.hasMore })
  }

  private val titles = "SELECT group_concat(title) FROM (SELECT title FROM article ORDER BY id)"

  @Test
  fun `a statement that writes is refused with its own failure, and the file is as it was`() {
    filled()
    val attempts =
        listOf(
            "INSERT INTO article VALUES (9, 'new', 'kept')",
            "UPDATE article SET title = 'changed'",
            "DELETE FROM article",
            "DROP TABLE article",
            "CREATE TABLE other (a INTEGER)",
            "WITH gone AS (SELECT 1) DELETE FROM article",
            "REPLACE INTO article VALUES (1, 'x', 'y')",
        )

    for (sql in attempts) {
      assertEquals(
          ReadOnlyFailure.QUERY_REJECTED,
          rig.failure { rig.read(sql, listOf("title")) },
          sql,
      )
    }

    assertEquals("alpha,beta,gamma", rig.scalar(titles))
  }

  @Test
  fun `more than one statement is refused, however the second is hidden, and the file is as it was`() {
    filled()
    val attempts =
        listOf(
            "SELECT title FROM article; DELETE FROM article",
            "SELECT title FROM article) ; DELETE FROM article WHERE (1 = 1",
            "SELECT title FROM article /* ; */ ; DELETE FROM article",
            "SELECT title FROM article;\nDROP TABLE article",
            "SELECT 'it''s' AS title; DELETE FROM article",
        )

    for (sql in attempts) {
      assertEquals(
          ReadOnlyFailure.QUERY_REJECTED,
          rig.failure { rig.read(sql, listOf("title")) },
          sql,
      )
    }

    assertEquals("alpha,beta,gamma", rig.scalar(titles))
  }

  @Test
  fun `a statement that changes the connection or makes it writable again is refused`() {
    filled()
    val attempts =
        listOf(
            "PRAGMA query_only = OFF",
            "PRAGMA query_only = OFF; DELETE FROM article",
            "PRAGMA journal_mode = DELETE",
            "PRAGMA writable_schema = ON",
            "ATTACH DATABASE ':memory:' AS other",
            "SELECT 1; PRAGMA query_only = OFF; DELETE FROM article",
        )

    for (sql in attempts) {
      assertEquals(
          ReadOnlyFailure.QUERY_REJECTED,
          rig.failure { rig.read(sql, listOf("title")) },
          sql,
      )
    }

    assertEquals("alpha,beta,gamma", rig.scalar(titles))
    assertEquals("wal", rig.scalar("PRAGMA journal_mode"))
    // The next read is as read-only as the first: nothing of an attempt is left on any connection.
    assertEquals(
        ReadOnlyFailure.QUERY_REJECTED,
        rig.failure { rig.read("DELETE FROM article", listOf("title")) },
    )
  }

  @Test
  fun `a connection opened to read refuses a change even when one reaches it`() {
    filled()
    val profile = rig.profiles.find("sqlite")!!

    JdbcConnector.open(profile, rig.settings(), null, reading = true).use { connection ->
      assertFailsWith<java.sql.SQLException> {
        connection.createStatement().use { it.executeUpdate("DELETE FROM article") }
      }
    }

    assertEquals("alpha,beta,gamma", rig.scalar(titles))
  }

  @Test
  fun `a query that names what the file does not have is a mismatch of structure`() {
    filled()

    assertEquals(
        ReadOnlyFailure.SCHEMA_MISMATCH,
        rig.failure { rig.read("SELECT title FROM no_such_table", listOf("title")) },
    )
    assertEquals(
        ReadOnlyFailure.SCHEMA_MISMATCH,
        rig.failure { rig.read("SELECT title, no_such_column FROM article", listOf("title")) },
    )
    // The query is fine; it is the column the view declares that the query does not return.
    assertEquals(
        ReadOnlyFailure.SCHEMA_MISMATCH,
        rig.failure { rig.read("SELECT title FROM article", listOf("title", "declared_only")) },
    )
    // A name in double quotes is not taken for text when it is not a column.
    assertEquals(
        ReadOnlyFailure.SCHEMA_MISMATCH,
        rig.failure { rig.read("SELECT title FROM article", listOf("\"title\"x")) },
    )
  }

  @Test
  fun `a file that is not there is a mismatch of structure and is not made by the read`() {
    assertEquals(
        ReadOnlyFailure.SCHEMA_MISMATCH,
        rig.failure { rig.read("SELECT 1 AS one", listOf("one")) },
    )

    assertEquals(false, java.nio.file.Files.exists(rig.file))
    assertEquals(emptyList(), java.nio.file.Files.list(rig.root).use { it.toList() })
  }

  @Test
  fun `a page larger than the rows the resource allows, or than its bytes, is too large`() {
    rig.execute(
        "PRAGMA journal_mode = WAL",
        "CREATE TABLE t (a TEXT)",
        "INSERT INTO t VALUES ('${"x".repeat(60)}'), ('${"y".repeat(60)}'), ('${"z".repeat(60)}')",
    )

    assertEquals(
        ReadOnlyFailure.RESULT_TOO_LARGE,
        rig.failure {
          rig.read("SELECT a FROM t", listOf("a"), limit = 3, extraSettings = "\"maxRows\":2")
        },
    )
    assertEquals(
        ReadOnlyFailure.RESULT_TOO_LARGE,
        rig.failure {
          rig.read(
              "SELECT a FROM t",
              listOf("a"),
              limit = 3,
              extraSettings = "\"maxResponseBytes\":100",
          )
        },
    )
    rig.read("SELECT a FROM t", listOf("a"), limit = 2, extraSettings = "\"maxRows\":2")
    // The limit on rows is on the rows there are in the page, not on the size of page asked for.
    rig.read("SELECT a FROM t LIMIT 2", listOf("a"), limit = 50, extraSettings = "\"maxRows\":2")
  }

  @Test
  fun `a value of a parameter is only ever a value, wherever the name is used`() {
    filled()
    val hostile = "x' OR 1 = 1 --"
    val lookalikes = listOf(hostile, "kept'; DELETE FROM article; --", ":state", "?", ":a ?")

    val none = lookalikes.map {
      rig.read(
          "SELECT title FROM article WHERE state = :state",
          listOf("title"),
          mapOf("state" to it),
      )
    }
    val twice =
        rig.read(
            "SELECT title FROM article WHERE :state IS NULL OR state = :state ORDER BY id",
            listOf("title"),
            mapOf("state" to "kept"),
        )
    val absent =
        rig.read(
            "SELECT title FROM article WHERE :state IS NULL OR state = :state",
            listOf("title"),
            mapOf("state" to null),
        )
    val inText =
        rig.read(
            "SELECT ':state' AS title, \"x:y\" /* :state */ FROM (SELECT 1 AS \"x:y\") WHERE :n = 7",
            listOf("title"),
            mapOf("n" to 7L),
        )

    none.forEach { assertEquals(emptyList(), it.rows) }
    assertEquals(listOf(listOf<Any?>("alpha"), listOf("gamma")), twice.rows)
    assertEquals(3, absent.rows.size)
    assertEquals(listOf(listOf<Any?>(":state")), inText.rows)
    assertEquals("3", rig.scalar("SELECT count(*) FROM article"))
  }

  @Test
  fun `a query that a name cannot be bound for, or that has another kind of parameter, is rejected`() {
    filled()
    val queries =
        listOf(
            "SELECT title FROM article WHERE state = :state" to emptyMap<String, Any?>(),
            "SELECT title FROM article WHERE state = ?" to mapOf("state" to "kept"),
            "SELECT title FROM article WHERE state = :state AND id > ?" to mapOf("state" to "kept"),
            "SELECT title FROM article WHERE state = @state" to mapOf("state" to "kept"),
            "SELECT title FROM article WHERE state = \$state" to mapOf("state" to "kept"),
            "SELECT title FROM article WHERE id = ?1" to mapOf("state" to "kept"),
        )
    for ((sql, parameters) in queries) {
      assertEquals(
          ReadOnlyFailure.QUERY_REJECTED,
          rig.failure { rig.read(sql, listOf("title"), parameters) },
          sql,
      )
    }
  }

  @Test
  fun `a read that runs away stops when it is cancelled, and says so`() {
    filled()
    val forever =
        "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c) SELECT count(*) AS n FROM c"
    val read = rig.query(forever, listOf("n"))
    val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
    try {
      val running = pool.submit<ReadOnlyFailure> { rig.failure { read.run() } }
      Thread.sleep(300)

      read.cancel()

      assertEquals(
          ReadOnlyFailure.CANCELLED,
          running.get(10, java.util.concurrent.TimeUnit.SECONDS),
      )
      // A read cancelled before it began does not begin.
      assertEquals(ReadOnlyFailure.CANCELLED, rig.failure { read.run() })
    } finally {
      pool.shutdownNow()
    }
  }
}
