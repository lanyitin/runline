package dev.lawlan.runline.accessors.jdbc

import dev.lawlan.runline.accessors.Invalidation
import dev.lawlan.runline.core.ResourceFailure
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a run does with the statements of a `jdbc-pool` resource of the kind `sqlite`, against a
 * real SQLite file in a real directory (WI-1003): the SQL reaches the database as written, values
 * pass as JDK types, a transaction is the run's, and the file is the resource root's.
 */
class SqliteBindingTest {
  private val rig = SqliteRig()

  @AfterTest fun close() = rig.close()

  private val host by lazy { rig.host() }

  @Test
  fun `the threads of the binding do not belong to the class loader of the run that started them`() {
    val runLoader = java.net.URLClassLoader(emptyArray())
    val own = Thread.currentThread()
    val before = own.contextClassLoader
    own.contextClassLoader = runLoader
    try {
      rig.update(host, "CREATE TABLE t (a INTEGER)")
      rig.query(host, "SELECT a FROM t")
    } finally {
      own.contextClassLoader = before
    }

    val workers =
        Thread.getAllStackTraces().keys.filter {
          it.isAlive && it.name.startsWith("jdbc-statement")
        }
    assertTrue(workers.isNotEmpty(), "the statements ran on threads of the binding")
    // A run that ends with threads holding its class loader is reported as leaving them running.
    assertEquals(
        emptyList(),
        workers.filter { it.contextClassLoader === runLoader }.map { it.name },
    )
  }

  @Test
  fun `a statement makes the file when it is first needed, and not before`() {
    val binding = host

    assertFalse(Files.exists(rig.root.resolve("digest.db")))
    assertEquals(0L, rig.update(binding, "CREATE TABLE t (a INTEGER)"))

    assertTrue(Files.isRegularFile(rig.root.resolve("digest.db")))
  }

  @Test
  fun `a directory that is not there is not made, and the run is told the file cannot be opened`() {
    val binding = rig.host(path = "missing/dir/digest.db")

    val failed = assertFailsWith<Failed> { rig.query(binding, "SELECT 1") }

    assertEquals(ResourceFailure.CONNECTION_FAILED, failed.failure)
    assertFalse(Files.exists(rig.root.resolve("missing")))
  }

  @Test
  fun `queries, updates with parameters and a transaction work as for any jdbc-pool`() {
    rig.update(host, "CREATE TABLE t (a INTEGER, b TEXT)")
    assertEquals(1L, rig.update(host, "INSERT INTO t VALUES (?, ?)", listOf(1L, "x")))
    rig.value(host, "jdbc.begin")
    rig.update(host, "INSERT INTO t VALUES (2, 'y')")
    rig.value(host, "jdbc.rollback")
    rig.value(host, "jdbc.begin")
    rig.update(host, "INSERT INTO t VALUES (3, 'z')")
    rig.value(host, "jdbc.commit")

    val rows = rig.query(host, "SELECT a, b FROM t ORDER BY a")

    assertEquals(listOf(mapOf("a" to 1L, "b" to "x"), mapOf("a" to 3L, "b" to "z")), rows)
  }

  @Test
  fun `a query without parameters keeps a question mark of the text as it is`() {
    assertEquals("a?b", rig.query(host, "SELECT 'a?b' AS s").single()["s"])
  }

  @Test
  fun `values pass as the kinds a run can give and take, whatever the column is declared as`() {
    rig.update(
        host,
        "CREATE TABLE v (i INTEGER, r REAL, t TEXT, b BLOB, n TEXT, flag BOOLEAN, d DATETIME)",
    )
    rig.update(
        host,
        "INSERT INTO v VALUES (?, ?, ?, ?, ?, ?, ?)",
        listOf(
            9_000_000_000L,
            1.5,
            "text",
            byteArrayOf(1, 2, 3),
            null,
            true,
            "2020-01-02 03:04:05",
        ),
    )

    val row = rig.query(host, "SELECT * FROM v").single()

    assertEquals(9_000_000_000L, row["i"])
    assertEquals(1.5, row["r"])
    assertEquals("text", row["t"])
    assertContentEquals(byteArrayOf(1, 2, 3), row["b"] as ByteArray)
    assertNull(row["n"])
    assertEquals(1L, row["flag"], "SQLite keeps a boolean as the integer 1")
    assertEquals("2020-01-02 03:04:05", row["d"])
    assertEquals(
        listOf("i", "r", "t", "b", "n", "flag", "d"),
        row.keys.toList(),
    )
    // Small integers come out as Long too, and a decimal as the text it was bound as.
    assertEquals(5L, rig.query(host, "SELECT 5 AS x").single()["x"])
    rig.update(host, "CREATE TABLE m (amount NUMERIC)")
    rig.update(host, "INSERT INTO m VALUES (?)", listOf(java.math.BigDecimal("12.50")))
    assertEquals(12.5, rig.query(host, "SELECT amount FROM m").single()["amount"])
  }

  @Test
  fun `a constraint it refuses is told by category and state, and its text and values are not`() {
    rig.update(
        host,
        "CREATE TABLE people (email TEXT PRIMARY KEY, boss TEXT REFERENCES people(email), age INTEGER NOT NULL CHECK (age > 0))",
    )
    rig.update(host, "INSERT INTO people VALUES ('dana-private@example.org', NULL, 5)")

    fun refusal(sql: String) =
        assertFailsWith<Failed> { rig.update(host, sql) }
            .also {
              assertEquals(ResourceFailure.SQL_ERROR, it.failure)
              val told = it.answer.toString()
              assertFalse(told.contains("dana"), told)
              assertFalse(told.contains("people"), told)
            }

    assertEquals(
        "23505",
        refusal("INSERT INTO people VALUES ('dana-private@example.org', NULL, 5)").sqlState,
    )
    assertEquals(
        "23503",
        refusal("INSERT INTO people VALUES ('x@example.org', 'nobody@example.org', 5)").sqlState,
    )
    assertEquals(
        "23502",
        refusal("INSERT INTO people VALUES ('y@example.org', NULL, NULL)").sqlState,
    )
    assertEquals("23514", refusal("INSERT INTO people VALUES ('z@example.org', NULL, -1)").sqlState)
    assertNull(refusal("SELEC 1").sqlState)
  }

  @Test
  fun `the file is in write-ahead mode with foreign keys on, whatever a run does to its connection`() {
    rig.update(host, "CREATE TABLE t (a INTEGER)")

    assertEquals("wal", rig.query(host, "PRAGMA journal_mode").single()["journal_mode"])
    assertEquals(1L, rig.query(host, "PRAGMA foreign_keys").single()["foreign_keys"])
    assertEquals(
        "wal",
        rig.external { c ->
          c.createStatement().use { s ->
            s.executeQuery("PRAGMA journal_mode").use {
              it.next()
              it.getString(1)
            }
          }
        },
    )

    // A run that turns them off finds them on again in the next run.
    rig.update(host, "PRAGMA foreign_keys = OFF")
    host.invalidateAll(Invalidation.RUN_ENDED)
    val next = rig.host()
    assertEquals(1L, rig.query(next, "PRAGMA foreign_keys").single()["foreign_keys"])
  }

  @Test
  fun `the answer is limited in rows as for any jdbc-pool`() {
    val limited = rig.host(extra = """"maxRows":3""")
    rig.update(limited, "CREATE TABLE t (a INTEGER)")
    for (i in 1..5) rig.update(limited, "INSERT INTO t VALUES ($i)")

    assertEquals(3, rig.query(limited, "SELECT a FROM t LIMIT 3").size)
    assertEquals(
        ResourceFailure.RESPONSE_TOO_LARGE,
        assertFailsWith<Failed> { rig.query(limited, "SELECT a FROM t") }.failure,
    )
  }

  @Test
  fun `nothing a run set up on its connection, a temporary table or a pragma, is left for the next run`() {
    rig.update(host, "CREATE TEMP TABLE scratch (a INTEGER)")
    rig.update(host, "PRAGMA query_only = ON")
    host.invalidateAll(Invalidation.RUN_ENDED)

    val next = rig.host()

    assertEquals(
        emptyList(),
        rig.query(next, "SELECT name FROM sqlite_temp_master WHERE name = 'scratch'"),
    )
    assertEquals(0L, rig.query(next, "PRAGMA query_only").single()["query_only"])
    assertEquals(0L, rig.update(next, "CREATE TABLE after (a INTEGER)"))
  }

  @Test
  fun `another program can open the file read-only while the runs use it, and reads what is committed`() {
    rig.update(host, "CREATE TABLE t (a INTEGER)")
    rig.update(host, "INSERT INTO t VALUES (1)")
    rig.value(host, "jdbc.begin")
    rig.update(host, "INSERT INTO t VALUES (2)")

    val seen =
        rig.external(readOnly = true) { c ->
          c.createStatement().use { s ->
            s.executeQuery("SELECT a FROM t ORDER BY a").use { rows ->
              generateSequence { if (rows.next()) rows.getLong(1) else null }.toList()
            }
          }
        }
    val written = runCatching {
      rig.external(readOnly = true) { c ->
        c.createStatement().use { it.executeUpdate("INSERT INTO t VALUES (3)") }
      }
    }

    assertEquals(listOf(1L), seen)
    assertTrue(written.isFailure, "a read-only program wrote to the file")
    rig.value(host, "jdbc.commit")
    assertEquals(listOf(1L, 2L), rig.query(host, "SELECT a FROM t ORDER BY a").map { it["a"] })
  }
}
