package dev.lawlan.runline.runner

import dev.lawlan.runline.core.Pipeline
import dev.lawlan.runline.runner.isolated.RunEntry
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Path

/**
 * A run's own root class loader. Its parent is only the JDK platform class loader, so the run sees
 * the Runner and core code it is given plus the pipeline jar, and nothing of the host.
 */
internal class RunClassLoader(runId: String, jar: Path, runtimeClasspath: List<URL>) :
    URLClassLoader(
        "run-$runId",
        (runtimeClasspath + jar.toUri().toURL()).toTypedArray(),
        platform,
    ) {

  companion object {
    private val platform: ClassLoader = getPlatformClassLoader()

    /**
     * Where the code that must exist inside every run loader (Runner entry, core, stdlib) lives.
     */
    fun defaultRuntimeClasspath(): List<URL> =
        listOf(RunEntry::class.java, Pipeline::class.java, Unit::class.java)
            .map { it.protectionDomain.codeSource.location }
            .distinct()
  }
}
