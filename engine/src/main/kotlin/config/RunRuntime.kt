package dev.lawlan.runline.engine.config

import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/**
 * The code every run's class loader is given: the Runner, core and the Kotlin library, as jars of
 * their own, apart from the Engine (ADR-001). Run class loaders have only the JDK above them, so
 * they see exactly these jars and the pipeline's; the Engine's own jar is never among them.
 */
class RunRuntime(val classpath: List<URL>) {
  companion object {
    private val REQUIRED =
        mapOf(
            "the Runner" to "dev/lawlan/runline/runner/isolated/RunEntry.class",
            "core" to "dev/lawlan/runline/core/Pipeline.class",
            "the Kotlin library" to "kotlin/Unit.class",
        )
    private const val ENGINE_PREFIX = "dev/lawlan/runline/engine/"

    /** The jars in [dir]; refuses a directory that does not hold exactly what a run needs. */
    fun fromDirectory(dir: Path): RunRuntime {
      if (!Files.isDirectory(dir)) {
        throw ConfigurationException("runs.runtimeDir $dir is not a directory")
      }
      val jars =
          Files.list(dir).use { files ->
            files.filter { it.isRegularFile() && it.extension == "jar" }.sorted().toList()
          }
      val entries = jars.associateWith { jar -> ZipFile(jar.toFile()).use { zip -> zip.names() } }
      REQUIRED.forEach { (what, entry) ->
        if (entries.values.none { entry in it }) {
          throw ConfigurationException("runs.runtimeDir $dir does not contain $what ($entry)")
        }
      }
      entries.forEach { (jar, names) ->
        if (names.any { it.startsWith(ENGINE_PREFIX) }) {
          throw ConfigurationException(
              "runs.runtimeDir $dir holds ${jar.fileName}, which contains Engine classes; " +
                  "a run would see the Engine. Give it only the Runner, core and Kotlin jars"
          )
        }
      }
      return RunRuntime(jars.map { it.toUri().toURL() })
    }

    private fun ZipFile.names(): Set<String> = entries().asSequence().map { it.name }.toSet()
  }
}
