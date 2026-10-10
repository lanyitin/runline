package dev.lawlan.runline.engine.run

import dev.lawlan.runline.accessors.support.PermissionsEnforced
import dev.lawlan.runline.accessors.support.TestDirectories
import dev.lawlan.runline.engine.artifact.PostgresArtifactStore
import dev.lawlan.runline.engine.artifact.PostgresDefinitionStore
import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.support.CapturedLogs
import dev.lawlan.runline.engine.support.StoredPipelines
import dev.lawlan.runline.engine.support.migratedDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.UUID
import kotlin.test.*

/**
 * The copies of runs' jars that a previous Engine process could not delete are removed when the
 * Engine starts; nothing else in the same directory is touched (WI-67).
 */
class LeftoverRunJarsTest {
  private val dir: Path = TestDirectories.forThisTest("leftover-jars")
  private val temp: Path = Files.createDirectories(dir.resolve("tmp"))
  private val dataSource = dataSourceOf(migratedDatabase())
  private val pipelines = StoredPipelines(PostgresArtifactStore(dataSource), dir)
  private val definitions = PostgresDefinitionStore(dataSource)
  private val runs = PostgresRunStore(dataSource)
  private val now = Instant.parse("2026-10-10T00:00:00Z")
  private var versions = 0

  /** A run of this Engine, recorded in its database, that has moved to [state]. */
  private fun run(state: RunState): UUID {
    val name = "p${versions++}"
    val hash = pipelines.save("v-$name", name)
    val id =
        runs
            .insert(
                NewRun(
                    UUID.randomUUID(),
                    definitions.find(hash, "alice", name)!!.id,
                    RunSource.Manual("alice"),
                    emptyMap(),
                    now,
                    null,
                )
            )
            .id
    if (state.terminal) runs.finish(id, state, now)
    else if (state != RunState.QUEUED) runs.advance(id, state, now)
    return id
  }

  /** The jar of [runId] as the scheduler writes it, left behind by a previous process. */
  private fun leftover(runId: UUID): Path =
      RunJarFiles.create(temp, runId).also { Files.writeString(it, "a jar") }

  private fun names(): Set<String> =
      Files.list(temp).use { files -> files.map { it.fileName.toString() }.toList().toSet() }

  @Test
  fun `the jar a previous process left for a run that ended is removed`() {
    val interrupted = leftover(run(RunState.INTERRUPTED))
    val succeeded = leftover(run(RunState.SUCCEEDED))

    val removed = LeftoverRunJars(temp, runs).remove()

    assertEquals(2, removed)
    assertFalse(Files.exists(interrupted))
    assertFalse(Files.exists(succeeded))
  }

  @Test
  fun `files of other programs and files named alike that are not the Engine's are left alone`() {
    val ended = run(RunState.INTERRUPTED)
    val outside = Files.writeString(dir.resolve("outside.jar"), "not in the directory")
    val kept =
        listOf(
            // Named as the Engine names them, for runs this Engine has never had.
            RunJarFiles.create(temp, UUID.randomUUID()),
            Files.createFile(temp.resolve("run-${UUID.randomUUID()}-123.jar")),
            // Named almost alike.
            Files.createFile(temp.resolve("run-8298843296306708515.jar")),
            Files.createFile(temp.resolve("run-$ended.jar")),
            Files.createFile(temp.resolve("run-$ended-123.jar.part")),
            Files.createFile(temp.resolve("x-run-$ended-123.jar")),
            Files.createFile(temp.resolve("run-$ended-12a.jar")),
            Files.createFile(temp.resolve("RUN-$ended-123.jar")),
            // Not a file the Engine writes, though named as one.
            Files.createDirectory(temp.resolve("run-$ended-124.jar")),
            Files.createSymbolicLink(temp.resolve("run-$ended-125.jar"), outside),
            // Of other programs.
            Files.createFile(temp.resolve("notes.txt")),
            Files.createFile(temp.resolve("upload-123.jar")),
        )
    val before = names()

    val removed = LeftoverRunJars(temp, runs).remove()

    assertEquals(0, removed)
    assertEquals(before, names())
    assertEquals(kept.map { it.fileName.toString() }.toSet(), names())
    assertEquals("not in the directory", Files.readString(outside))
  }

  @Test
  fun `the jar of a run that has not ended is left alone`() {
    val running = leftover(run(RunState.RUNNING))
    val queued = leftover(run(RunState.QUEUED))

    assertEquals(0, LeftoverRunJars(temp, runs).remove())

    assertTrue(Files.exists(running))
    assertTrue(Files.exists(queued))
  }

  @Test
  fun `a leftover that cannot be removed is said in the log and nothing is thrown`() =
      // As for any user, also for root (WI-57): otherwise the directory's permission is not
      // enforced.
      PermissionsEnforced.run {
        val stuck = leftover(run(RunState.INTERRUPTED))
        Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("r-x------"))
        try {
          check(!Files.isWritable(temp)) {
            "permissions are not enforced here, so the test would prove nothing"
          }
          CapturedLogs().use { logs ->
            assertEquals(0, LeftoverRunJars(temp, runs).remove())

            assertTrue(Files.exists(stuck))
            assertTrue(
                logs.lines.any { it.startsWith("WARN") && it.contains(stuck.fileName.toString()) },
                logs.lines.joinToString("\n"),
            )
          }
        } finally {
          Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rwx------"))
        }
      }

  @Test
  fun `a directory that cannot be looked through is said in the log and nothing is thrown`() {
    val missing = dir.resolve("no-such-directory")

    CapturedLogs().use { logs ->
      assertEquals(0, LeftoverRunJars(missing, runs).remove())

      assertTrue(
          logs.lines.any { it.startsWith("WARN") && it.contains(missing.toString()) },
          logs.lines.joinToString("\n"),
      )
    }
  }
}
