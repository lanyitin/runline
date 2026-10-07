package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.accessors.jdbc.RealPostgres
import dev.lawlan.runline.analyzer.AllowListEntry
import dev.lawlan.runline.analyzer.Verdict
import dev.lawlan.runline.engine.artifact.Visibility
import dev.lawlan.runline.engine.auth.ApiIdentity
import dev.lawlan.runline.engine.auth.Role
import dev.lawlan.runline.engine.run.CancelResult
import dev.lawlan.runline.engine.run.RunState
import dev.lawlan.runline.engine.secret.KeystoreSecretStore
import dev.lawlan.runline.engine.secret.SecretValue
import dev.lawlan.runline.engine.support.Keystores
import dev.lawlan.runline.engine.support.RunHarness
import dev.lawlan.runline.engine.support.RunHarness.Companion.usingTyped
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.UUID
import kotlin.test.*

/**
 * `jdbc-pool` resources through the whole run machinery with everything real: PostgreSQL for the
 * Engine and another for the resource, the real coordinator, scheduler and Runner (a class loader
 * per run), pipelines compiled from source, a real PKCS12 keystore (WI-48, ADR-019). What the
 * database saw is what the database says: its sessions, its statements, its accounts.
 */
class JdbcResourceRunTest {
  private val keystores = Keystores()
  private val passwordFile = keystores.passwordFile()
  private val database = RealPostgres.newDatabase()
  private val role = "run_" + UUID.randomUUID().toString().replace("-", "").take(12)
  private var password = "pw-" + UUID.randomUUID().toString().replace("-", "")
  private val keystore: Path = keystores.pkcs12("jdbc-run.p12", mapOf("db-pw" to password))
  private val secrets =
      KeystoreSecretStore.open(keystore, SecretValue(Files.readString(passwordFile).trim()))
  private val harnesses = mutableListOf<RunHarness>()
  private val admin = ApiIdentity("root", Role.ADMIN)
  private val declaration = usingTyped("db" to "jdbc-pool")

  init {
    database.createRole(role, password, "GRANT ALL ON SCHEMA public TO $role")
  }

  private fun harness(maxConcurrent: Int = 4) =
      RunHarness(
              maxConcurrent = maxConcurrent,
              resourceWaitTimeout = Duration.ofHours(1),
              secrets = secrets,
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

  @AfterTest
  fun closeAll() {
    harnesses.forEach { it.close() }
    secrets.close()
    database.close()
  }

  private fun settings(extra: String = "") =
      """{"kind":"postgresql","host":"${RealPostgres.host}","port":${RealPostgres.port},"database":"${database.name}","username":"$role"${if (extra.isEmpty()) "" else ",$extra"}}"""

  private fun define(h: RunHarness, capacity: Int = 1, extra: String = "") =
      h.defineJdbc("db", settings(extra), capacity, alias = "db-pw")

  private fun write(file: String, text: String) =
      "context.getFiles().writeText(FileScope.PIPELINE_SHARED, \"$file\", $text);"

  /** A pipeline body that runs [statement] and writes how it ended to the shared file [file]. */
  private fun attempt(file: String, statement: String) =
      """
      JdbcAccessor db = context.getAccessors().jdbcPool("db");
      String result;
      try { result = "ok|" + db.query("$statement").getRows(); }
      catch (ResourceAccessException e) { result = e.getFailure().name() + "|" + e.getSqlState(); }
      ${write(file, "result")}
      """
          .trimIndent()

  private fun RunHarness.result(pipeline: String, file: String) =
      Files.readString(shared(pipeline, file))

  private fun await(what: String, seconds: Long = 30, condition: () -> Boolean) {
    val deadline = System.nanoTime() + seconds * 1_000_000_000
    while (!condition()) {
      check(System.nanoTime() < deadline) { "gave up waiting for $what" }
      Thread.sleep(20)
    }
  }

  @Test
  fun `a pipeline that uses the accessor of a jdbc-pool resource is still safe`() {
    val h = harness()
    val hash = h.upload("caller", attempt("answer", "SELECT 1"), declaration = declaration)

    assertEquals(Verdict.SAFE, h.definitions.find(hash, "caller")!!.verdict)
  }

  @Test
  fun `a run queries the database with the account and the password of the resource`() {
    val h = harness()
    define(h)
    val hash =
        h.upload("caller", attempt("answer", "SELECT current_user"), declaration = declaration)

    val run = h.awaitEnd(h.start(hash, "caller"))

    assertEquals(RunState.SUCCEEDED, run.state, run.failure?.message)
    assertEquals("ok|[[$role]]", h.result("caller", "answer"))
  }

  @Test
  fun `a run that ends leaves the next one a connection as new, and the connection is the same one`() {
    val h = harness()
    define(h)
    val dirty =
        h.upload(
            "dirty",
            """
            JdbcAccessor db = context.getAccessors().jdbcPool("db");
            ${write("pid", "String.valueOf(db.query(\"SELECT pg_backend_pid()\").getRows().get(0).get(0))")}
            db.update("SET search_path TO pg_catalog");
            db.update("CREATE TEMP TABLE scratch (a int)");
            db.query("SELECT pg_advisory_lock(4242)");
            db.begin();
            db.update("CREATE TABLE public.left_open (a int)");
            """
                .trimIndent(),
            declaration = declaration,
        )
    val clean =
        h.upload(
            "clean",
            """
            JdbcAccessor db = context.getAccessors().jdbcPool("db");
            ${write("seen", """db.query("SELECT pg_backend_pid(), current_setting('search_path'), to_regclass('pg_temp.scratch') IS NULL, pg_try_advisory_lock(4242), to_regclass('public.left_open') IS NULL").getRows().toString()""")}
            """
                .trimIndent(),
            declaration = declaration,
        )

    for (name in listOf("dirty" to dirty, "clean" to clean)) {
      val run = h.awaitEnd(h.start(name.second, name.first))
      assertEquals(RunState.SUCCEEDED, run.state, run.failure?.message)
    }

    val pid = h.result("dirty", "pid")
    assertEquals("[[$pid, \"\$user\", public, true, true, true]]", h.result("clean", "seen"))
  }

  @Test
  fun `runs up to the capacity use their share at once and a run over it waits for the resource`() {
    val h = harness()
    define(h, capacity = 2)
    val holder =
        h.upload(
            "holder",
            """
            JdbcAccessor db = context.getAccessors().jdbcPool("db");
            db.query("SELECT 1");
            ${write("held", "\"x\"")}
            try { while (!context.getFiles().exists(FileScope.PIPELINE_SHARED, "release")) Thread.sleep(10); }
            catch (InterruptedException e) { throw new RuntimeException(e); }
            """
                .trimIndent(),
            declaration = declaration,
        )
    val third = h.upload("third", attempt("answer", "SELECT 3"), declaration = declaration)
    val a = h.start(holder, "holder")
    await("the first to hold the resource") { Files.exists(h.shared("holder", "held")) }
    val b = h.start(holder, "holder")
    await("the second connection") { database.sessions(user = role) == 2 }
    val c = h.start(third, "third")

    h.await(c, RunState.WAITING_FOR_RESOURCES)
    assertEquals(2, h.jdbcPools.activeConnections("db"))
    Files.writeString(h.shared("holder", "release"), "x")

    assertEquals(RunState.SUCCEEDED, h.awaitEnd(a).state)
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(b).state)
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(c).state)
    assertEquals("ok|[[3]]", h.result("third", "answer"))
    assertEquals(2, database.sessions(user = role), "more connections than the capacity allows")
    assertEquals(0, h.jdbcPools.activeConnections("db"))
  }

  @Test
  fun `a forced release cancels the statement in flight, the database stops it, and the next waiter gets the resource`() {
    val h = harness(maxConcurrent = 2)
    define(h)
    val holder =
        h.upload(
            "holder",
            """
            JdbcAccessor db = context.getAccessors().jdbcPool("db");
            db.update("SET search_path TO pg_catalog");
            String first;
            try { db.query("SELECT pg_sleep(60)"); first = "ok"; }
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
            attempt("answer", "SELECT current_setting('search_path')"),
            declaration = declaration,
        )
    val first = h.start(holder, "holder")
    await("the statement in the database") { database.active("runline") == 1 }
    val second = h.start(next, "next")
    h.await(second, RunState.WAITING_FOR_RESOURCES)

    h.coordinator!!.forceRelease("db", first, admin)

    assertEquals(RunState.SUCCEEDED, h.awaitEnd(second).state)
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(first).state)
    assertEquals("CANCELLED|FORCE_RELEASED", h.result("holder", "outcome"))
    assertEquals("ok|[[\"\$user\", public]]", h.result("next", "answer"))
    assertEquals(0, database.active("runline"), "the statement was not stopped in the database")
  }

  @Test
  fun `cancelling a run cuts the statement in flight and gives its connections back`() {
    val h = harness()
    define(h)
    val hash =
        h.upload(
            "sleeper",
            """
            JdbcAccessor db = context.getAccessors().jdbcPool("db");
            db.begin();
            db.update("CREATE TABLE half_done (a int)");
            db.query("SELECT pg_sleep(60)");
            """
                .trimIndent(),
            declaration = declaration,
        )
    val run = h.start(hash, "sleeper")
    await("the statement in the database") { database.active("runline") == 1 }

    assertEquals(CancelResult.CancellationRequested, h.service.cancel(run, Visibility.All))

    h.awaitEnd(run)
    await("the connections to come back") { h.jdbcPools.activeConnections("db") == 0 }
    await("the statement to stop") { database.active("runline") == 0 }
    assertEquals(0, h.jdbcPools.holders("db"))
    assertEquals(0, database.idleInTransaction("runline"))
    assertEquals(null, database.scalar("SELECT to_regclass('half_done')::text"))
  }

  @Test
  fun `a run that gets the resource after the password changed uses the new one, and one that holds it goes on with the old`() {
    val h = harness()
    define(h, capacity = 2)
    val holder =
        h.upload(
            "holder",
            """
            JdbcAccessor db = context.getAccessors().jdbcPool("db");
            db.query("SELECT 1");
            ${write("held", "\"x\"")}
            try { while (!context.getFiles().exists(FileScope.PIPELINE_SHARED, "release")) Thread.sleep(10); }
            catch (InterruptedException e) { throw new RuntimeException(e); }
            ${write("after", "String.valueOf(db.query(\"SELECT current_user\").getRows())")}
            """
                .trimIndent(),
            declaration = declaration,
        )
    val later =
        h.upload("later", attempt("answer", "SELECT current_user"), declaration = declaration)
    val first = h.start(holder, "holder")
    await("the first to hold the resource") { Files.exists(h.shared("holder", "held")) }

    val rotated = "pw-" + UUID.randomUUID().toString().replace("-", "")
    database.execute("ALTER ROLE $role PASSWORD '$rotated'")
    val copy = keystore.resolveSibling("jdbc-run.p12.new")
    Files.copy(keystore, copy, StandardCopyOption.REPLACE_EXISTING)
    keystores.deleteEntry(copy, "db-pw", passwordFile)
    keystores.importSecret(copy, "db-pw", rotated, passwordFile)
    Files.move(copy, keystore, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    secrets.reload()
    password = rotated
    val second = h.start(later, "later")

    assertEquals(RunState.SUCCEEDED, h.awaitEnd(second).state)
    assertEquals("ok|[[$role]]", h.result("later", "answer"))
    assertEquals(2, database.sessions(user = role), "the generations do not have a connection each")
    Files.writeString(h.shared("holder", "release"), "x")
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(first).state)
    assertEquals("[[$role]]", h.result("holder", "after"))
    await("the old generation to close") { database.sessions(user = role) == 1 }
  }

  @Test
  fun `a run refused for another resource does not keep the connections it was given`() {
    // Whichever of the two the Engine binds first, the refusal of the other must let go of it.
    for ((index, names) in listOf("a-db" to "z-file", "z-db" to "a-file").withIndex()) {
      val h = harness()
      h.defineJdbc(names.first, settings(), alias = "db-pw")
      h.defineFile(names.second, "x.txt")
      val pipeline = "both$index"
      val hash =
          h.upload(
              pipeline,
              "JdbcAccessor db = context.getAccessors().jdbcPool(\"${names.first}\");",
              declaration = usingTyped(names.first to "jdbc-pool", names.second to "file"),
          )
      h.resourceRoot.toFile().deleteRecursively()

      val run = h.awaitEnd(h.start(hash, pipeline))

      assertEquals(RunState.FAILED, run.state, names.toString())
      assertEquals(
          0,
          h.jdbcPools.holders(names.first),
          "the refused run still holds the pool of ${names.first} (bound before ${names.second}?)",
      )
    }
  }

  @Test
  fun `a pipeline jar that brings a driver of its own does not change the driver the resource uses`() {
    val h = harness()
    define(h)
    // A class with the name of the Engine's driver, in the pipeline's jar, which says when it is
    // used for anything but being loaded.
    val impostor =
        """
        package org.postgresql;
        public class Driver {
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
            try { own = String.valueOf(Class.forName("org.postgresql.Driver").getField("WHO").get(null)); }
            catch (Exception e) { own = "not found: " + e; }
            JdbcAccessor db = context.getAccessors().jdbcPool("db");
            String via = String.valueOf(db.query("SELECT current_user").getRows());
            ${write("answer", "own + \"|\" + via")}
            """
                .trimIndent(),
            declaration = declaration,
            extraClasses = mapOf("org.postgresql.Driver" to impostor),
        )

    val run = h.awaitEnd(h.start(hash, "impostor"))

    assertEquals(RunState.SUCCEEDED, run.state, run.failure?.message)
    assertEquals("the pipeline's own|[[$role]]", h.result("impostor", "answer"))
  }
}
