package dev.lawlan.runline.engine.packaged

import dev.lawlan.runline.accessors.support.TestDirectories
import dev.lawlan.runline.engine.config.DatabaseConfig
import dev.lawlan.runline.engine.db.DatabaseMigrator
import dev.lawlan.runline.engine.support.ManagedProcess
import dev.lawlan.runline.engine.support.PostgresTestContainer
import dev.lawlan.runline.engine.support.TestTimeouts
import java.net.ServerSocket
import java.nio.file.Path
import kotlin.test.*

/**
 * The migration as a deployment runs it (WI-38): `engine.jar` alone, no Gradle, the database given
 * through `POSTGRES_URL`, `POSTGRES_USER` and `POSTGRES_PASSWORD`, against a real PostgreSQL. The
 * answer is the exit code and the output.
 */
class PackagedMigrationTest {
  private val dist = Path.of(System.getProperty("runline.dist"))
  private val javaBin = System.getProperty("runline.java")
  private val work: Path = TestDirectories.forThisTest("packaged-migration")
  private val processes = mutableListOf<ManagedProcess>()

  @AfterTest
  fun stop() {
    processes.forEach { it.close() }
  }

  private var runs = 0

  /**
   * Runs the migration from the jar with only the database variables; returns exit code, output.
   */
  private fun migrate(database: DatabaseConfig): Pair<Int, String> {
    val log = work.resolve("migrate-${runs++}.log")
    val process =
        ManagedProcess.start(
                "the migration process",
                listOf(
                    javaBin,
                    "-cp",
                    dist.resolve("engine.jar").toString(),
                    "dev.lawlan.runline.engine.db.MigrateKt",
                ),
                log,
                environment =
                    mapOf(
                        "POSTGRES_URL" to database.url,
                        "POSTGRES_USER" to database.user,
                        "POSTGRES_PASSWORD" to database.password,
                    ),
                directory = work,
            )
            .also { processes += it }
    return process.awaitExit(TestTimeouts.migration) to process.output()
  }

  @Test
  fun `an empty database is migrated, and running it again changes nothing`() {
    val database = PostgresTestContainer.newDatabase()

    val (first, firstOutput) = migrate(database)
    assertEquals(0, first, firstOutput)
    DatabaseMigrator(database).requireUpToDate()

    val (second, secondOutput) = migrate(database)
    assertEquals(0, second, secondOutput)
    assertTrue(secondOutput.contains("Applied 0 migration(s)"), secondOutput)
  }

  @Test
  fun `a database that cannot be reached fails with a reason and without the password`() {
    val database = PostgresTestContainer.newDatabase()
    val closedPort = ServerSocket(0).use { it.localPort }
    val unreachable =
        DatabaseConfig(
            "jdbc:postgresql://localhost:$closedPort/runline",
            database.user,
            "pw-for-the-unreachable-0123",
        )

    val (code, output) = migrate(unreachable)

    assertNotEquals(0, code, output)
    assertTrue(output.contains("Migration failed"), output)
    assertFalse(output.contains(unreachable.password), output)
  }

  @Test
  fun `a refused password fails with a reason and without the password`() {
    val database = PostgresTestContainer.newDatabase()
    val wrong = database.copy(password = "wrong-pw-0123456789")

    val (code, output) = migrate(wrong)

    assertNotEquals(0, code, output)
    assertTrue(output.contains("Migration failed"), output)
    assertFalse(output.contains(wrong.password), output)
  }
}
