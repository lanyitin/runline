package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.analyzer.AllowListEntry
import dev.lawlan.runline.analyzer.Verdict
import dev.lawlan.runline.engine.artifact.Visibility
import dev.lawlan.runline.engine.auth.ApiIdentity
import dev.lawlan.runline.engine.auth.Role
import dev.lawlan.runline.engine.run.CancelResult
import dev.lawlan.runline.engine.run.RunState
import dev.lawlan.runline.engine.support.RunHarness
import dev.lawlan.runline.engine.support.RunHarness.Companion.usingTyped
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Duration
import kotlin.test.*

/**
 * `sqlite` resources through the whole run machinery with everything real (WI-1003, ADR-024):
 * PostgreSQL for the Engine, a real SQLite file in a real resource root, the real coordinator,
 * scheduler and Runner (a class loader per run), pipelines compiled from source. What the file
 * holds is what another program reads from it.
 */
class SqliteResourceRunTest {
  private val harnesses = mutableListOf<RunHarness>()
  private val admin = ApiIdentity("root", Role.ADMIN)
  private val declaration = usingTyped("db" to "jdbc-pool")
  private val forever =
      "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c) SELECT count(*) FROM c"

  private fun harness(maxConcurrent: Int = 4) =
      RunHarness(
              maxConcurrent = maxConcurrent,
              resourceWaitTimeout = Duration.ofHours(1),
              allowList =
                  listOf(
                          "java.lang",
                          "java.util",
                          "java.io",
                          "java.time",
                          "kotlin",
                          "org.jetbrains.annotations",
                      )
                      .map { AllowListEntry(it) },
          )
          .also { harnesses += it }

  @AfterTest fun closeAll() = harnesses.forEach { it.close() }

  private fun define(h: RunHarness, capacity: Int = 1, extra: String = "") =
      h.defineJdbc(
          "db",
          """{"kind":"sqlite","path":"digest.db"${if (extra.isEmpty()) "" else ",$extra"}}""",
          capacity,
      )

  private fun write(file: String, text: String) =
      "context.getFiles().writeText(FileScope.PIPELINE_SHARED, \"$file\", $text);"

  private fun RunHarness.result(pipeline: String, file: String) =
      Files.readString(shared(pipeline, file))

  private fun await(what: String, seconds: Long = 30, condition: () -> Boolean) {
    val deadline = System.nanoTime() + seconds * 1_000_000_000
    while (!condition()) {
      check(System.nanoTime() < deadline) { "gave up waiting for $what" }
      Thread.sleep(20)
    }
  }

  /** Reads the file as another program would, read-only, while the Engine goes on using it. */
  private fun <T> RunHarness.outside(sql: String, read: (java.sql.ResultSet) -> T): T {
    // open_mode 1 is SQLITE_OPEN_READONLY.
    val readOnly = java.util.Properties().apply { setProperty("open_mode", "1") }
    return DriverManager.getConnection("jdbc:sqlite:" + resourceRoot.resolve("digest.db"), readOnly)
        .use { c -> c.createStatement().use { s -> s.executeQuery(sql).use(read) } }
  }

  @Test
  fun `a pipeline that uses a sqlite resource through its accessor is still safe`() {
    val h = harness()
    val hash =
        h.upload(
            "caller",
            """
            JdbcAccessor db = context.getAccessors().jdbcPool("db");
            db.query("SELECT 1");
            """
                .trimIndent(),
            declaration = declaration,
        )

    assertEquals(Verdict.SAFE, h.definitions.find(hash, "alice", "caller")!!.verdict)
  }

  @Test
  fun `runs that write at once all succeed, and another program reads what they committed`() {
    val h = harness(maxConcurrent = 4)
    define(h, capacity = 4)
    val writer =
        h.upload(
            "writer",
            """
            JdbcAccessor db = context.getAccessors().jdbcPool("db");
            db.update("CREATE TABLE IF NOT EXISTS t (run TEXT, n INTEGER)");
            for (int n = 1; n <= 25; n++) {
              db.begin();
              db.update("INSERT INTO t VALUES (?, ?)", java.util.Arrays.<Object>asList(String.valueOf(System.identityHashCode(getClass().getClassLoader())), n));
              db.commit();
            }
            """
                .trimIndent(),
            declaration = declaration,
        )

    val runs = (1..4).map { h.start(writer, "writer") }

    runs.forEach { assertEquals(RunState.SUCCEEDED, h.awaitEnd(it).state, "$it") }
    h.outside("SELECT count(*), count(DISTINCT run) FROM t") {
      it.next()
      assertEquals(100, it.getInt(1))
      assertEquals(4, it.getInt(2))
    }
  }

  @Test
  fun `a run reads what is committed while another holds the write lock, and does not wait for it`() {
    val h = harness(maxConcurrent = 2)
    define(h, capacity = 2)
    val holder =
        h.upload(
            "holder",
            """
            JdbcAccessor db = context.getAccessors().jdbcPool("db");
            db.update("CREATE TABLE t (a INTEGER)");
            db.update("INSERT INTO t VALUES (1)");
            db.begin();
            db.update("INSERT INTO t VALUES (2)");
            ${write("held", "\"x\"")}
            try { while (!context.getFiles().exists(FileScope.PIPELINE_SHARED, "release")) Thread.sleep(10); }
            catch (InterruptedException e) { throw new RuntimeException(e); }
            db.commit();
            """
                .trimIndent(),
            declaration = declaration,
        )
    val reader =
        h.upload(
            "reader",
            """
            JdbcAccessor db = context.getAccessors().jdbcPool("db");
            ${write("seen", "String.valueOf(db.query(\"SELECT count(*) AS c FROM t\").maps().get(0).get(\"c\"))")}
            """
                .trimIndent(),
            declaration = declaration,
        )
    val first = h.start(holder, "holder")
    await("the writer to hold the lock") { Files.exists(h.shared("holder", "held")) }

    val second = h.start(reader, "reader")

    assertEquals(RunState.SUCCEEDED, h.awaitEnd(second).state)
    assertEquals("1", h.result("reader", "seen"))
    h.outside("SELECT count(*) FROM t") {
      it.next()
      assertEquals(1, it.getInt(1))
    }
    Files.writeString(h.shared("holder", "release"), "x")
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(first).state)
    h.outside("SELECT count(*) FROM t") {
      it.next()
      assertEquals(2, it.getInt(1))
    }
  }

  @Test
  fun `a forced release stops the statement in flight, rolls back, and the next waiter gets the resource and the lock`() {
    val h = harness(maxConcurrent = 2)
    define(h)
    val holder =
        h.upload(
            "holder",
            """
            JdbcAccessor db = context.getAccessors().jdbcPool("db");
            db.update("CREATE TABLE t (a INTEGER)");
            db.begin();
            db.update("INSERT INTO t VALUES (1)");
            ${write("started", "\"x\"")}
            String first;
            try { db.query("$forever"); first = "ok"; }
            catch (ResourceAccessException e) { first = e.getFailure().name(); }
            String second;
            try { db.query("SELECT 1"); second = "ok"; }
            catch (ResourceAccessException e) { second = e.getFailure().name(); }
            ${write("outcome", "first + \"|\" + second")}
            """
                .trimIndent(),
            declaration = declaration,
        )
    val next =
        h.upload(
            "next",
            """
            JdbcAccessor db = context.getAccessors().jdbcPool("db");
            long inserted = db.update("INSERT INTO t VALUES (2)");
            ${write("answer", "inserted + \"|\" + db.query(\"SELECT count(*) AS c FROM t\").maps().get(0).get(\"c\")")}
            """
                .trimIndent(),
            declaration = declaration,
        )
    val first = h.start(holder, "holder")
    await("the statement to run") { Files.exists(h.shared("holder", "started")) }
    Thread.sleep(500)
    val second = h.start(next, "next")
    h.await(second, RunState.WAITING_FOR_RESOURCES)

    h.coordinator!!.forceRelease("db", first, admin)

    assertEquals(RunState.SUCCEEDED, h.awaitEnd(second).state)
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(first).state)
    assertEquals("CANCELLED|FORCE_RELEASED", h.result("holder", "outcome"))
    assertEquals("1|1", h.result("next", "answer"))
    assertEquals(0, h.jdbcPools.activeConnections("db"))
  }

  @Test
  fun `cancelling a run stops the statement in flight, rolls back and gives its connections back`() {
    val h = harness()
    define(h)
    val hash =
        h.upload(
            "sleeper",
            """
            JdbcAccessor db = context.getAccessors().jdbcPool("db");
            db.update("CREATE TABLE t (a INTEGER)");
            db.begin();
            db.update("INSERT INTO t VALUES (1)");
            ${write("started", "\"x\"")}
            db.query("$forever");
            """
                .trimIndent(),
            declaration = declaration,
        )
    val run = h.start(hash, "sleeper")
    await("the statement to run") { Files.exists(h.shared("sleeper", "started")) }
    Thread.sleep(500)

    assertEquals(CancelResult.CancellationRequested, h.service.cancel(run, Visibility.All))

    h.awaitEnd(run)
    await("the connections to come back") { h.jdbcPools.activeConnections("db") == 0 }
    assertEquals(0, h.jdbcPools.holders("db"))
    h.outside("SELECT count(*) FROM t") {
      it.next()
      assertEquals(0, it.getInt(1))
    }
    // The lock is let go: another program writes at once.
    DriverManager.getConnection("jdbc:sqlite:" + h.resourceRoot.resolve("digest.db")).use {
      it.createStatement().use { s -> s.execute("PRAGMA busy_timeout = 0") }
      it.createStatement().use { s -> s.execute("INSERT INTO t VALUES (9)") }
    }
  }

  @Test
  fun `a pipeline jar that brings a driver of its own does not change the driver the resource uses`() {
    val h = harness()
    define(h)
    val impostor =
        """
        package org.sqlite;
        public class JDBC {
          public static final String WHO = "the pipeline's own";
          public java.sql.Connection connect(String url, java.util.Properties info) {
            throw new IllegalStateException("the pipeline's own driver was asked to connect");
          }
        }
        """
            .trimIndent()
    val hash =
        h.upload(
            "impostor",
            """
            String own;
            try { own = String.valueOf(Class.forName("org.sqlite.JDBC").getField("WHO").get(null)); }
            catch (Exception e) { own = "not found: " + e; }
            JdbcAccessor db = context.getAccessors().jdbcPool("db");
            db.update("CREATE TABLE IF NOT EXISTS t (a INTEGER)");
            db.update("INSERT INTO t VALUES (1)");
            ${write("answer", "own + \"|\" + db.query(\"SELECT count(*) AS c FROM t\").maps().get(0).get(\"c\")")}
            """
                .trimIndent(),
            declaration = declaration,
            extraClasses = mapOf("org.sqlite.JDBC" to impostor),
        )

    val answers =
        (1..3).map {
          val run = h.awaitEnd(h.start(hash, "impostor"))
          assertEquals(RunState.SUCCEEDED, run.state, run.failure?.message)
          h.result("impostor", "answer")
        }

    assertEquals(
        listOf("the pipeline's own|1", "the pipeline's own|2", "the pipeline's own|3"),
        answers,
    )
  }
}
