package dev.lawlan.runline.accessors.jdbc

import dev.lawlan.runline.core.ResourceFailure
import java.math.BigDecimal
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a run does with the statements of a `jdbc-pool` resource, against a real PostgreSQL (WI-48):
 * the SQL reaches the database as written, values pass as JDK types, and a transaction is the
 * run's. Every call goes through the host's [dev.lawlan.runline.accessors.BoundResources], as it
 * does in the Engine.
 */
class JdbcBindingTest {
  private val rig = JdbcRig()

  @AfterTest fun close() = rig.close()

  private val host by lazy { rig.host() }

  @Test
  fun `a query gives the columns and the values of the database as JDK types`() {
    val row =
        rig.query(
                host,
                """
                SELECT 1::int2 AS a, 2::int4 AS b, 3::int8 AS c, 1.5::float4 AS d, 2.5::float8 AS e,
                       12.50::numeric(10,2) AS f, true AS g, 'x'::text AS h,
                       '\xdeadbeef'::bytea AS i, '2020-01-02'::date AS j,
                       'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::uuid AS k, '{"a":1}'::jsonb AS l,
                       ARRAY[1,2] AS m, NULL::int AS n, 12.5::money AS o, 'abc'::varchar(3) AS p,
                       '2020-01-02 03:04:05+00'::timestamptz AS q
                """
                    .trimIndent(),
            )
            .single()

    assertEquals(
        listOf("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o", "p", "q"),
        row.keys.toList(),
    )
    assertEquals(1L, row["a"])
    assertEquals(2L, row["b"])
    assertEquals(3L, row["c"])
    assertEquals(1.5, row["d"])
    assertEquals(2.5, row["e"])
    assertEquals(BigDecimal("12.50"), row["f"])
    assertEquals(true, row["g"])
    assertEquals("x", row["h"])
    assertContentEquals(
        byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte()),
        row["i"] as ByteArray,
    )
    assertEquals("2020-01-02", row["j"])
    assertEquals("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11", row["k"])
    assertEquals("""{"a": 1}""", row["l"])
    assertEquals("{1,2}", row["m"])
    assertNull(row["n"])
    assertTrue((row["o"] as String).contains("12.50"), "money is text, not a floating point number")
    assertEquals("abc", row["p"])
    assertTrue((row["q"] as String).startsWith("2020-01-02 03:04:05"), row["q"].toString())
    // Whatever the database holds, only these kinds come out.
    for (value in row.values) {
      assertTrue(
          value == null ||
              value is String ||
              value is Boolean ||
              value is Long ||
              value is Double ||
              value is BigDecimal ||
              value is ByteArray,
          "${value?.javaClass}",
      )
    }
  }

  @Test
  fun `parameters of each kind reach the database as what they are`() {
    val row =
        rig.query(
                host,
                "SELECT pg_typeof(?::text) AS t, ?::int + 1 AS i, ?::float8 * 2 AS d, NOT ?::bool AS b, " +
                    "length(?::bytea) AS l, ?::numeric + 1 AS n, ?::text IS NULL AS isnull",
                listOf("text", 41L, 1.25, true, byteArrayOf(1, 2, 3), BigDecimal("0.50"), null),
            )
            .single()

    assertEquals("text", row["t"])
    assertEquals(42L, row["i"])
    assertEquals(2.5, row["d"])
    assertEquals(false, row["b"])
    assertEquals(3L, row["l"])
    assertEquals(BigDecimal("1.50"), row["n"])
    assertEquals(true, row["isnull"])
  }

  @Test
  fun `text fills a column of another type, as the database reads it`() {
    rig.update(host, "CREATE TABLE typed (id uuid, at timestamptz, n int, d date)")

    val inserted =
        rig.update(
            host,
            "INSERT INTO typed VALUES (?, ?, ?, ?)",
            listOf(
                "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11",
                "2020-01-02 03:04:05+00",
                "7",
                "2020-02-03",
            ),
        )

    assertEquals(1L, inserted)
    val row =
        rig.query(
                host,
                "SELECT id, n, d FROM typed WHERE id = ?",
                listOf("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11"),
            )
            .single()
    assertEquals(7L, row["n"])
    assertEquals("2020-02-03", row["d"])
  }

  @Test
  fun `an update gives the number of rows it changed`() {
    rig.update(host, "CREATE TABLE t (a int PRIMARY KEY, b text)")
    rig.update(host, "INSERT INTO t SELECT g, 'x' FROM generate_series(1, 5) g")

    assertEquals(2L, rig.update(host, "UPDATE t SET b = ? WHERE a <= ?", listOf("y", 2L)))
    assertEquals(0L, rig.update(host, "DELETE FROM t WHERE a > 100"))
    assertEquals(5L, rig.update(host, "DELETE FROM t"))
  }

  @Test
  fun `the dialect of the database is carried out as written`() {
    rig.update(host, "CREATE TABLE kv (k text PRIMARY KEY, v int, tags jsonb)")

    val upserted =
        rig.query(
            host,
            "INSERT INTO kv VALUES (?, 1, '{\"a\": 1}') ON CONFLICT (k) DO UPDATE SET v = kv.v + 1 RETURNING v",
            listOf("one"),
        )
    val again =
        rig.query(
            host,
            "INSERT INTO kv VALUES (?, 1, '{\"a\": 1}') ON CONFLICT (k) DO UPDATE SET v = kv.v + 1 RETURNING v",
            listOf("one"),
        )
    val dollar =
        rig.query(
            host,
            "SELECT \$tag\$it's 'quoted'\$tag\$ AS s, (ARRAY[3,1,2])[2] AS e, 5::bigint::text AS c",
        )
    val series = rig.query(host, "SELECT * FROM generate_series(1, 3) AS g(n) ORDER BY n DESC")

    assertEquals(1L, upserted.single()["v"])
    assertEquals(2L, again.single()["v"])
    assertEquals("it's 'quoted'", dollar.single()["s"])
    assertEquals(1L, dollar.single()["e"])
    assertEquals("5", dollar.single()["c"])
    assertEquals(listOf(3L, 2L, 1L), series.map { it["n"] })
  }

  @Test
  fun `a question mark of the text is the database's own when there are no parameters`() {
    rig.update(host, "CREATE TABLE docs (j jsonb)")
    rig.update(host, "INSERT INTO docs VALUES ('{\"a\": 1}')")

    val found = rig.query(host, "SELECT count(*) AS n FROM docs WHERE j ? 'a'")

    assertEquals(1L, found.single()["n"])
  }

  @Test
  fun `the JDBC escape syntax is not processed, the text goes to the database untouched`() {
    val e = assertFailsWith<Failed> { rig.query(host, "SELECT {d '2020-01-02'} AS d") }

    // PostgreSQL itself says it is a syntax error; a driver that rewrote it would have answered.
    assertEquals(ResourceFailure.SQL_ERROR, e.failure)
    assertEquals("42601", e.sqlState)
  }

  @Test
  fun `a statement with no result set is refused as a query and a result set as an update`() {
    rig.update(host, "CREATE TABLE t (a int)")

    val asQuery = assertFailsWith<Failed> { rig.query(host, "INSERT INTO t VALUES (1)") }
    val asUpdate = assertFailsWith<Failed> { rig.update(host, "SELECT 1") }

    assertEquals(ResourceFailure.SQL_ERROR, asQuery.failure)
    assertEquals(ResourceFailure.SQL_ERROR, asUpdate.failure)
  }

  @Test
  fun `a transaction is the run's and its changes are seen by others only when it is committed`() {
    rig.update(host, "CREATE TABLE t (a int)")

    rig.value(host, "jdbc.begin")
    rig.update(host, "INSERT INTO t VALUES (1)")
    val inside = rig.query(host, "SELECT count(*) AS n, pg_backend_pid() AS pid FROM t").single()
    val seenByOthers = rig.db.scalar("SELECT count(*) FROM t")
    rig.value(host, "jdbc.commit")
    val afterCommit = rig.db.scalar("SELECT count(*) FROM t")

    assertEquals(1L, inside["n"])
    assertEquals("0", seenByOthers)
    assertEquals("1", afterCommit)
  }

  @Test
  fun `a rolled back transaction leaves nothing, and every statement of it ran in one session`() {
    rig.update(host, "CREATE TABLE t (a int)")

    rig.value(host, "jdbc.begin")
    val first = rig.query(host, "SELECT pg_backend_pid() AS pid").single()["pid"]
    rig.update(host, "INSERT INTO t VALUES (1)")
    val second = rig.query(host, "SELECT pg_backend_pid() AS pid").single()["pid"]
    rig.value(host, "jdbc.rollback")

    assertEquals(first, second)
    assertEquals("0", rig.db.scalar("SELECT count(*) FROM t"))
  }

  @Test
  fun `without a transaction every statement is its own`() {
    rig.update(host, "CREATE TABLE t (a int)")

    rig.update(host, "INSERT INTO t VALUES (1)")

    assertEquals("1", rig.db.scalar("SELECT count(*) FROM t"))
  }

  @Test
  fun `a transaction that is begun twice, or ended without being begun, is refused`() {
    val none = assertFailsWith<Failed> { rig.value(host, "jdbc.commit") }
    val noneRollback = assertFailsWith<Failed> { rig.value(host, "jdbc.rollback") }
    rig.value(host, "jdbc.begin")
    val twice = assertFailsWith<Failed> { rig.value(host, "jdbc.begin") }

    assertEquals(ResourceFailure.TRANSACTION_STATE, none.failure)
    assertEquals(ResourceFailure.TRANSACTION_STATE, noneRollback.failure)
    assertEquals(ResourceFailure.TRANSACTION_STATE, twice.failure)
    // The transaction that was open is still the one that is open.
    rig.value(host, "jdbc.rollback")
  }

  @Test
  fun `after a failed commit or an error inside a transaction it can be rolled back and begun again`() {
    rig.update(host, "CREATE TABLE t (a int PRIMARY KEY)")
    rig.value(host, "jdbc.begin")
    rig.update(host, "INSERT INTO t VALUES (1)")
    val duplicate = assertFailsWith<Failed> { rig.update(host, "INSERT INTO t VALUES (1)") }
    val aborted = assertFailsWith<Failed> { rig.query(host, "SELECT 1") }
    rig.value(host, "jdbc.rollback")

    rig.value(host, "jdbc.begin")
    rig.update(host, "INSERT INTO t VALUES (2)")
    rig.value(host, "jdbc.commit")

    assertEquals("23505", duplicate.sqlState)
    assertEquals("25P02", aborted.sqlState)
    assertEquals("2", rig.db.scalar("SELECT a FROM t"))
  }
}
