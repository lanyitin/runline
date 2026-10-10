package dev.lawlan.runline.accessors.suite

import java.io.File
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The behavior of `jdbc-pool` accessors on a SQLite file that every host must show, the same for
 * the Engine and the development entry (WI-1003, as WI-48 does for PostgreSQL): what a pipeline
 * does with statements and transactions, what it is told when the database refuses, how values
 * pass, what happens to a statement that runs too long, and that run after run, each with a class
 * loader of its own, uses the same file. The database is a real SQLite file in a real directory,
 * the pipelines are compiled for real.
 */
abstract class SqliteBehaviorSuite {
  protected abstract fun newRig(): AccessorRig

  private val rig: AccessorRig by lazy { newRig() }
  private val typed = mapOf("db" to "jdbc-pool")

  @AfterTest fun close() = rig.close()

  private fun define(extra: String = "", path: String = "digest.db") {
    rig.defineJdbc(
        "db",
        """{"kind":"sqlite","path":"$path"${if (extra.isEmpty()) "" else ",$extra"}}""",
        RigKey.None,
    )
  }

  private fun write(text: String) =
      "context.getFiles().writeText(FileScope.PIPELINE_SHARED, \"out\", $text);"

  /** Runs [sql] on the file as another program would, and gives the first column as text. */
  private fun outside(sql: String, path: String = "digest.db"): String? =
      DriverManager.getConnection("jdbc:sqlite:" + File(rig.resourceRoot.toFile(), path)).use { c ->
        c.createStatement().use { s ->
          s.executeQuery(sql).use { if (it.next()) it.getString(1) else null }
        }
      }

  @Test
  fun `a pipeline runs statements and a transaction through its accessor`() {
    define()

    val outcome =
        rig.run(
            """
            JdbcAccessor db = context.getAccessors().jdbcPool("db");
            db.update("CREATE TABLE t (a INTEGER, b TEXT)");
            long inserted = db.update("INSERT INTO t VALUES (?, ?)", java.util.Arrays.<Object>asList(1, "x"));
            db.begin();
            db.update("INSERT INTO t VALUES (2, 'y')");
            db.rollback();
            db.begin();
            db.update("INSERT INTO t VALUES (3, 'z')");
            db.commit();
            JdbcRows rows = db.query("SELECT a, b FROM t ORDER BY a");
            ${write("inserted + \"|\" + rows.getSize() + \"|\" + rows.getColumns() + \"|\" + rows.maps()")}
            """
                .trimIndent(),
            typed = typed,
        )

    assertTrue(outcome.succeeded, outcome.failure)
    assertEquals("1|2|[a, b]|[{a=1, b=x}, {a=3, b=z}]", outcome.shared("out"))
    assertEquals("2", outside("SELECT count(*) FROM t"))
    assertEquals("wal", outside("PRAGMA journal_mode"))
  }

  @Test
  fun `values pass as the kinds SQLite keeps them in`() {
    define()

    val outcome =
        rig.run(
            """
            JdbcAccessor db = context.getAccessors().jdbcPool("db");
            db.update("CREATE TABLE v (i INTEGER, r REAL, t TEXT, b BLOB, n TEXT, flag BOOLEAN)");
            db.update("INSERT INTO v VALUES (?, ?, ?, ?, ?, ?)",
                java.util.Arrays.<Object>asList(9000000000L, 1.5, "text", new byte[] {1, 2, 3}, null, true));
            java.util.Map<String, Object> row = db.query("SELECT * FROM v").maps().get(0);
            StringBuilder told = new StringBuilder();
            for (String column : db.query("SELECT * FROM v").getColumns()) {
              Object value = row.get(column);
              told.append(column).append('=')
                  .append(value == null ? "null" : value.getClass().getSimpleName())
                  .append(value instanceof byte[] ? java.util.Arrays.toString((byte[]) value) : "")
                  .append(' ');
            }
            ${write("told.toString().trim() + \"|\" + row.get(\"i\") + \"|\" + row.get(\"flag\")")}
            """
                .trimIndent(),
            typed = typed,
        )

    assertTrue(outcome.succeeded, outcome.failure)
    assertEquals(
        "i=Long r=Double t=String b=byte[][1, 2, 3] n=null flag=Long|9000000000|1",
        outcome.shared("out"),
    )
  }

  @Test
  fun `a refusal of the database is told by category and state, and its text and values are not`() {
    define()

    val outcome =
        rig.run(
            """
            JdbcAccessor db = context.getAccessors().jdbcPool("db");
            db.update("CREATE TABLE people (email TEXT PRIMARY KEY)");
            db.update("INSERT INTO people VALUES (?)", java.util.Arrays.<Object>asList("dana-private@example.org"));
            String result;
            try {
              db.update("INSERT INTO people VALUES (?)", java.util.Arrays.<Object>asList("dana-private@example.org"));
              result = "no failure";
            } catch (ResourceAccessException e) {
              result = e.getFailure() + "|" + e.getSqlState() + "|" + e.getMessage() + "|" + e;
            }
            ${write("result")}
            """
                .trimIndent(),
            typed = typed,
        )

    assertTrue(outcome.succeeded, outcome.failure)
    val told = outcome.shared("out")!!
    assertTrue(told.startsWith("SQL_ERROR|23505|"), told)
    assertFalse(told.contains("dana"), told)
    assertFalse(told.contains("people"), told)
    assertFalse(told.contains(rig.resourceRoot.toString()), told)
  }

  @Test
  fun `a file in a directory that is not there is told as a failure to connect, without its path`() {
    define(path = "missing/digest.db")

    val outcome =
        rig.run(
            """
            JdbcAccessor db = context.getAccessors().jdbcPool("db");
            String result;
            try { db.query("SELECT 1"); result = "no failure"; }
            catch (ResourceAccessException e) { result = e.getFailure() + "|" + e + "|" + e.getMessage(); }
            ${write("result")}
            """
                .trimIndent(),
            typed = typed,
        )

    assertTrue(outcome.succeeded, outcome.failure)
    val told = outcome.shared("out")!!
    assertTrue(told.startsWith("CONNECTION_FAILED|"), told)
    assertFalse(told.contains("missing"), told)
    assertFalse(told.contains(rig.resourceRoot.toString()), told)
    assertFalse(File(rig.resourceRoot.toFile(), "missing").exists())
  }

  @Test
  fun `connections that open a file that is not there yet at the same moment all succeed, in the log, with foreign keys`() {
    val rounds = 20
    val threads = 6
    for (round in 0 until rounds) {
      rig.defineJdbc(
          "db$round",
          """{"kind":"sqlite","path":"first-$round.db","connectionsPerRun":$threads}""",
          RigKey.None,
      )
    }
    val declared = (0 until rounds).associate { "db$it" to "jdbc-pool" }

    val outcome =
        rig.run(
            """
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool($threads);
            StringBuilder told = new StringBuilder();
            try {
              for (int round = 0; round < $rounds; round++) {
                final JdbcAccessor db = context.getAccessors().jdbcPool("db" + round);
                final java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
                java.util.List<java.util.concurrent.Future<String>> all = new java.util.ArrayList<>();
                for (int t = 0; t < $threads; t++) {
                  final int n = t;
                  all.add(pool.submit(() -> {
                    go.await();
                    db.update("CREATE TABLE IF NOT EXISTS t" + n + " (a INTEGER)");
                    return db.query("PRAGMA journal_mode").maps().get(0).get("journal_mode")
                        + "/" + db.query("PRAGMA foreign_keys").maps().get(0).get("foreign_keys");
                  }));
                }
                go.countDown();
                for (java.util.concurrent.Future<String> one : all) {
                  String seen = one.get();
                  if (!seen.equals("wal/1")) told.append(round).append(':').append(seen).append(' ');
                }
              }
            } catch (Exception e) {
              told.append("failed: ").append(e);
            } finally { pool.shutdownNow(); }
            ${write("told.length() == 0 ? \"all opened\" : told.toString()")}
            """
                .trimIndent(),
            typed = declared,
        )

    assertTrue(outcome.succeeded, outcome.failure)
    assertEquals("all opened", outcome.shared("out"))
    for (round in 0 until rounds) {
      assertEquals(
          "$threads",
          outside("SELECT count(*) FROM sqlite_master", path = "first-$round.db"),
          "round $round",
      )
    }
  }

  @Test
  fun `a statement that runs longer than allowed is stopped, and the file is free again`() {
    define(""""timeouts":{"statementMs":700}""")

    val outcome =
        rig.run(
            """
            JdbcAccessor db = context.getAccessors().jdbcPool("db");
            db.update("CREATE TABLE t (a INTEGER)");
            db.begin();
            db.update("INSERT INTO t VALUES (1)");
            String result;
            long started = System.nanoTime();
            try {
              db.query("WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c) SELECT count(*) FROM c");
              result = "no failure";
            } catch (ResourceAccessException e) { result = e.getFailure().name(); }
            ${write("result + \"|\" + ((System.nanoTime() - started) / 1000000 < 10000)")}
            """
                .trimIndent(),
            typed = typed,
        )

    assertTrue(outcome.succeeded, outcome.failure)
    assertEquals("TOTAL_TIMEOUT|true", outcome.shared("out"))
    // What the run had not committed went with it, and nothing holds the lock.
    assertEquals("0", outside("SELECT count(*) FROM t"))
    DriverManager.getConnection("jdbc:sqlite:" + File(rig.resourceRoot.toFile(), "digest.db")).use {
      it.createStatement().use { s -> s.execute("BEGIN IMMEDIATE") }
    }
  }

  @Test
  fun `run after run, each with a class loader of its own, uses the same file`() {
    define()
    val first =
        rig.run(
            """
            JdbcAccessor db = context.getAccessors().jdbcPool("db");
            db.update("CREATE TABLE runs (n INTEGER PRIMARY KEY AUTOINCREMENT, note TEXT)");
            """
                .trimIndent(),
            typed = typed,
        )
    assertTrue(first.succeeded, first.failure)

    val seen = mutableListOf<String?>()
    for (n in 1..5) {
      val outcome =
          rig.run(
              """
              JdbcAccessor db = context.getAccessors().jdbcPool("db");
              db.begin();
              db.update("INSERT INTO runs (note) VALUES (?)", java.util.Arrays.<Object>asList("run $n"));
              db.commit();
              ${write("db.query(\"SELECT count(*) AS c FROM runs\").maps().get(0).get(\"c\") + \"|\" + db.query(\"SELECT note FROM runs ORDER BY n DESC LIMIT 1\").maps().get(0).get(\"note\")")}
              """
                  .trimIndent(),
              typed = typed,
          )
      assertTrue(outcome.succeeded, "run $n: ${outcome.failure}")
      seen += outcome.shared("out")
    }

    assertEquals((1..5).map<Int, String?> { "$it|run $it" }, seen)
    assertEquals("5", outside("SELECT count(*) FROM runs"))
  }

  @Test
  fun `a pipeline that did not declare the resource as jdbc-pool gets no accessor`() {
    define()

    val outcome =
        rig.run(
            """
            String result;
            try { context.getAccessors().jdbcPool("db"); result = "got one"; }
            catch (ResourceAccessException e) { result = e.getFailure().name(); }
            ${write("result")}
            """
                .trimIndent(),
            named = setOf("db"),
        )

    assertTrue(outcome.succeeded, outcome.failure)
    assertEquals("NO_TYPE_DECLARED", outcome.shared("out"))
    assertFalse(File(rig.resourceRoot.toFile(), "digest.db").exists())
  }

  @Test
  fun `a recording notes the resource, its type and the kind of action and never the statement`() {
    define()
    if (!rig.records) return

    val outcome =
        rig.run(
            """
            JdbcAccessor db = context.getAccessors().jdbcPool("db");
            db.query("SELECT 'recorded-secret-text' AS s");
            db.update("CREATE TABLE r (a INTEGER)");
            """
                .trimIndent(),
            typed = typed,
        )

    assertTrue(outcome.succeeded, outcome.failure)
    val recorded = assertNotNull(outcome.recorded)
    assertTrue(recorded.contains("db") && recorded.contains("jdbc-pool"), recorded)
    assertFalse(recorded.contains("recorded-secret-text"), recorded)
    assertFalse(recorded.contains("CREATE TABLE"), recorded)
  }
}
