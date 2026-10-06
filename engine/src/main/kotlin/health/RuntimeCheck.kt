package dev.lawlan.runline.engine.health

import java.nio.file.Files
import java.nio.file.Path

/**
 * The directory that holds the code of every run is still there, with the jars it had at startup.
 * Only their presence is checked: the startup checked what is in them, and what this finds is a
 * volume that was unmounted or a directory that was removed.
 */
class RuntimeCheck(private val directory: Path, private val jars: List<Path>) : ReadinessCheck {
  override val name = "runtime"

  override fun check(): CheckResult {
    if (!Files.isDirectory(directory)) {
      return CheckResult.failed("the run runtime directory $directory does not exist")
    }
    val missing = jars.filterNot { Files.isRegularFile(it) }
    if (missing.isNotEmpty()) {
      return CheckResult.failed(
          "the run runtime lacks ${missing.joinToString { "${it.fileName}" }}"
      )
    }
    return CheckResult.OK
  }
}
