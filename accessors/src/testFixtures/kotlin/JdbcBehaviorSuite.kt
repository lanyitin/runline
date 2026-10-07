package dev.lawlan.runline.accessors.suite

import dev.lawlan.runline.accessors.jdbc.RealPostgres
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The behavior of `jdbc-pool` accessors every host must show, the same for the Engine and the
 * development entry (WI-48): what a pipeline does with statements and transactions, what it is told
 * when the database refuses, and what it never sees. The database is a real PostgreSQL, the
 * pipelines are compiled for real.
 */
abstract class JdbcBehaviorSuite {
  protected abstract fun newRig(): AccessorRig

  private val rig: AccessorRig by lazy { newRig() }
  private val database = RealPostgres.newDatabase()
  private val role = "suite_" + UUID.randomUUID().toString().replace("-", "").take(12)
  private val password = "pw-" + UUID.randomUUID().toString().replace("-", "")
  private val typed = mapOf("db" to "jdbc-pool")

  @AfterTest
  fun close() {
    rig.close()
    database.close()
  }

  private fun settings(user: String = role, host: String = RealPostgres.host) =
      """{"kind":"postgresql","host":"$host","port":${RealPostgres.port},"database":"${database.name}","username":"$user"}"""

  private fun define(key: RigKey = RigKey.Value(password), user: String = role) {
    database.createRole(role, password, "GRANT ALL ON SCHEMA public TO $role")
    rig.defineJdbc("db", settings(user), key)
  }

  private fun write(text: String) =
      "context.getFiles().writeText(FileScope.PIPELINE_SHARED, \"out\", $text);"

  @Test
  fun `a pipeline runs statements and a transaction through its accessor`() {
    define()

    val outcome =
        rig.run(
            """
            JdbcAccessor db = context.getAccessors().jdbcPool("db");
            db.update("CREATE TABLE t (a int, b text)");
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
    assertEquals("2", database.scalar("SELECT count(*) FROM t"))
  }

  @Test
  fun `a refusal of the database is told by category and state, and its text and values are not`() {
    define()

    val outcome =
        rig.run(
            """
            JdbcAccessor db = context.getAccessors().jdbcPool("db");
            db.update("CREATE TABLE people (email text PRIMARY KEY)");
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
  }

  @Test
  fun `a password that is wrong, and one the host does not have, are told as categories without any credential`() {
    define(RigKey.Value("not-the-password-0xBAD"))
    val wrong =
        rig.run(
            """
            JdbcAccessor db = context.getAccessors().jdbcPool("db");
            String result;
            try { db.query("SELECT 1"); result = "no failure"; }
            catch (ResourceAccessException e) {
              java.io.StringWriter text = new java.io.StringWriter();
              e.printStackTrace(new java.io.PrintWriter(text));
              result = e.getFailure() + "|" + e.getSqlState() + "|" + text;
            }
            ${write("result")}
            """
                .trimIndent(),
            typed = typed,
        )

    assertTrue(wrong.succeeded, wrong.failure)
    val told = wrong.shared("out")!!
    assertTrue(told.startsWith("DENIED|28P01|"), told)
    for (secret in listOf("0xBAD", password, role, RealPostgres.host, database.name)) {
      assertFalse(told.contains(secret), "the pipeline saw '$secret'")
    }
  }

  @Test
  fun `a password the host does not have leaves the resource without a connection`() {
    define(RigKey.Missing)

    val outcome =
        rig.run(
            """
            JdbcAccessor db = context.getAccessors().jdbcPool("db");
            String result;
            try { db.query("SELECT 1"); result = "no failure"; }
            catch (ResourceAccessException e) { result = e.getFailure().name(); }
            ${write("result")}
            """
                .trimIndent(),
            typed = typed,
        )

    assertTrue(outcome.succeeded, outcome.failure)
    assertEquals("SECRET_UNAVAILABLE", outcome.shared("out"))
    assertEquals(0, database.sessions())
  }

  @Test
  fun `a resource that was not declared as jdbc-pool gives no accessor`() {
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
    assertEquals(0, database.sessions())
  }

  @Test
  fun `the run's connections are given back when it ends`() {
    define()

    val outcome =
        rig.run(
            """
            JdbcAccessor db = context.getAccessors().jdbcPool("db");
            db.begin();
            db.update("CREATE TABLE left_open (a int)");
            ${write("\"done\"")}
            """
                .trimIndent(),
            typed = typed,
        )

    assertTrue(outcome.succeeded, outcome.failure)
    assertEquals(0, database.idleInTransaction("runline"))
    assertEquals(null, database.scalar("SELECT to_regclass('left_open')::text"))
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
            db.update("CREATE TABLE r (a int)");
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
