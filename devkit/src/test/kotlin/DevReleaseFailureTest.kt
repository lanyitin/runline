package dev.lawlan.runline.devkit

import dev.lawlan.runline.accessors.fake.FreezableForward
import dev.lawlan.runline.accessors.jdbc.RealPostgres
import dev.lawlan.runline.devkit.support.PipelineJars
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.jupiter.api.io.TempDir

/**
 * When giving back a `jdbc-pool` resource fails as a development run ends, what the developer sees
 * (ADR-007 "Run 終止時"): the run's own failure comes first and the release's is attached to it; a
 * release that fails after a run that did not is said as it is (WI-62). The database is a real
 * PostgreSQL behind a real TCP forward that stops answering, so the connection cannot be cleaned
 * and its cleaning fails within the limit of the type.
 */
class DevReleaseFailureTest {
  @TempDir lateinit var tmp: Path

  private val database = RealPostgres.newDatabase()
  private val role = "dev_" + UUID.randomUUID().toString().replace("-", "").take(12)
  private val password = "pw-" + UUID.randomUUID().toString().replace("-", "")
  private val forward = FreezableForward(RealPostgres.host, RealPostgres.port)
  private val buffer = ByteArrayOutputStream()
  private val output
    get() = buffer.toString(Charsets.UTF_8)

  init {
    database.createRole(role, password, "GRANT ALL ON SCHEMA public TO $role")
  }

  @AfterTest
  fun closeAll() {
    forward.close()
    database.close()
  }

  /**
   * Runs a pipeline that holds `db` in an open transaction until the network to the database has
   * stopped answering, and then does [then]; returns what the development entry made of it.
   */
  private fun execute(then: String): CompletableFuture<Int> {
    val project = tmp.resolve("project")
    Files.createDirectories(project.resolve("jdbc"))
    Files.writeString(
        project.resolve("jdbc/db.json"),
        """{"kind":"postgresql","host":"${forward.host}","port":${forward.port},""" +
            """"database":"${database.name}","username":"$role","secretAlias":"db-pw"}""",
    )
    val jar =
        PipelineJars.build(
            tmp,
            "P.jar",
            mapOf(
                "P" to
                    PipelineJars.pipeline(
                        "P",
                        "p",
                        """
                        JdbcAccessor db = context.getAccessors().jdbcPool("db");
                        db.begin();
                        db.query("SELECT 1");
                        context.getFiles().writeText(FileScope.PIPELINE_SHARED, "in-transaction", "x");
                        while (!context.getFiles().exists(FileScope.PIPELINE_SHARED, "end")) Thread.sleep(10);
                        $then
                        """
                            .trimIndent(),
                        "",
                        """, typedResources = {@TypedResource(name = "db", type = "jdbc-pool")}""",
                    )
            ),
        )
    val env =
        mapOf(
            "RUNLINE_ALLOW_LIST" to "java.lang,java.util,java.io",
            "RUNLINE_RESOURCES" to "db=jdbc-pool:jdbc/db.json",
            "RUNLINE_SECRET_DB_PW" to password,
        )
    val config = DevConfig.fromEnvironment(env, project).copy(waitLimit = Duration.ofSeconds(60))
    val shared = config.workspace.sharedRoot.resolve("p")
    val code = CompletableFuture.supplyAsync {
      DevSession(config, PrintStream(buffer, true, Charsets.UTF_8))
          .execute(DevArguments(jar, "P", emptyMap()), "dev-release")
    }
    val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
    while (!Files.exists(shared.resolve("in-transaction"))) {
      if (code.isDone || System.nanoTime() > deadline) {
        fail("the run did not open its transaction:\n$output")
      }
      Thread.sleep(20)
    }
    forward.freeze()
    Files.writeString(shared.resolve("end"), "x")
    return code
  }

  @Test
  fun `a run that fails and whose release fails too is shown with its own failure first, and the release's after it`() {
    val code =
        execute("""throw new IllegalStateException("the run's own failure");""")
            .get(60, TimeUnit.SECONDS)

    assertEquals(DevSession.EXIT_RUN_NOT_SUCCEEDED, code, output)
    val own = output.indexOf("java.lang.IllegalStateException: the run's own failure")
    assertTrue(own >= 0, "the run's own failure is not shown:\n$output")
    val release = output.indexOf("CONNECTION_FAILED", own)
    assertTrue(release > own, "the failure of the release is not shown after it:\n$output")
  }

  @Test
  fun `a release that fails after a run that succeeded is said as it is`() {
    val failure =
        try {
          execute("").get(60, TimeUnit.SECONDS)
          fail("the development entry did not say that the release failed:\n$output")
        } catch (e: ExecutionException) {
          e.cause!!
        }

    assertTrue("CONNECTION_FAILED" in failure.message.orEmpty(), "$failure")
    assertTrue("[status] SUCCEEDED" in output, output)
  }
}
