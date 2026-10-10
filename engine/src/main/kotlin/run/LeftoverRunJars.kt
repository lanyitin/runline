package dev.lawlan.runline.engine.run

import dev.lawlan.runline.engine.artifact.Visibility
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.UUID
import org.slf4j.LoggerFactory

/**
 * How the copy of a run's jar is named in the directory it is written to: the run's id, then the
 * random part the temporary file gets, so that a copy can be told apart from other files there.
 */
object RunJarFiles {
  private val name =
      Regex("run-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})-[0-9]+\\.jar")

  fun create(directory: Path, runId: UUID): Path =
      Files.createTempFile(directory, "run-$runId-", ".jar")

  /** The run [file] is the copy of a jar for, by its name alone; null for any other name. */
  fun runOf(file: Path): UUID? =
      name.matchEntire(file.fileName.toString())?.let { UUID.fromString(it.groupValues[1]) }
}

/**
 * Removes the copies of runs' jars that an earlier Engine process left in [directory] (04 "優雅關閉").
 * A file is such a copy only when it is a regular file named as [RunJarFiles] names them and its
 * run is one of this Engine's runs that has ended; everything else is left alone.
 */
class LeftoverRunJars(private val directory: Path, private val runs: RunStore) {
  private val log = LoggerFactory.getLogger(LeftoverRunJars::class.java)

  /**
   * Returns how many were removed. Nothing is thrown: what cannot be looked at or removed is said
   * in the log and left where it is, and the Engine starts all the same.
   */
  fun remove(): Int {
    val files =
        try {
          Files.list(directory).use { it.toList() }
        } catch (e: Exception) {
          log.warn("Cannot look for jars of runs an earlier process left in {}", directory, e)
          return 0
        }
    var removed = 0
    for (file in files) {
      try {
        if (isLeftover(file)) {
          Files.delete(file)
          removed++
        }
      } catch (e: Exception) {
        log.warn("Cannot remove {}, the jar of a run an earlier process left", file, e)
      }
    }
    if (removed > 0) {
      log.info("Removed {} jar(s) of runs an earlier process left in {}", removed, directory)
    }
    return removed
  }

  private fun isLeftover(file: Path): Boolean {
    val run = RunJarFiles.runOf(file) ?: return false
    return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) &&
        runs.find(run, Visibility.All)?.state?.terminal == true
  }
}
