package dev.lawlan.runline.engine.packaged

import dev.lawlan.runline.engine.support.ManagedProcess
import dev.lawlan.runline.engine.support.TestTimeouts
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.*

/**
 * How the build treats the Console (WI-31, ADR-015), checked by really building: each test makes a
 * project in a temporary directory that applies the script the Engine's build applies, and runs
 * Gradle (the project's own wrapper, as a process) on it. Node and npm are the real ones; the
 * probe's `shadowJar` and `packagedTest` are empty tasks standing in for the Engine's, so what is
 * tested is how the script ties itself to them.
 */
class ConsoleBuildTest {
  private val script = Path.of(System.getProperty("runline.consoleScript"))
  private val consoleSources = Path.of(System.getProperty("runline.consoleDir"))
  private val gradlew = System.getProperty("runline.gradlew")
  private val dir: Path = Files.createTempDirectory("console-project")
  private val logs: Path = Files.createTempDirectory("console-logs")

  private fun write(name: String, text: String) {
    val file = dir.resolve(name)
    file.parent.createDirectories()
    file.writeText(text)
  }

  /** The probe project; its Node version is [nodeVersion], by default what Node really is. */
  private fun project(nodeVersion: String = actualNodeVersion()) {
    write("settings.gradle.kts", "rootProject.name = \"probe\"\n")
    write(
        "build.gradle.kts",
        """
        apply(from = "$script")
        tasks.register("shadowJar")
        tasks.register("packagedTest")
        """
            .trimIndent() + "\n",
    )
    write(".node-version", "$nodeVersion\n")
  }

  private fun run(command: List<String>, directory: Path? = null): String {
    val process =
        ProcessBuilder(command)
            .redirectErrorStream(true)
            .apply { directory?.let { directory(it.toFile()) } }
            .start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { "$command failed: $output" }
    return output.trim()
  }

  private fun actualNodeVersion() = run(listOf("node", "--version")).removePrefix("v")

  /** Where the real Node really is: what a shim of a version manager stands for. */
  private fun realNodeBinary(): Path = Path.of(run(listOf("node", "-p", "process.execPath")))

  private class Build(val exitCode: Int, val output: String) {
    fun taskOutcome(task: String): String? =
        output
            .lines()
            .firstOrNull { it.startsWith("> Task :$task") }
            ?.removePrefix("> Task :$task")
            ?.trim()
  }

  private var builds = 0

  private fun gradle(
      vararg args: String,
      environment: Map<String, String> = emptyMap(),
  ): Build {
    val log = logs.resolve("gradle-${builds++}.log")
    ManagedProcess.start(
            "Gradle on the probe project",
            listOf(gradlew, "-p", dir.toString(), "--console=plain") + args,
            log,
            environment,
        )
        .use { process ->
          val exitCode = process.awaitExit(TestTimeouts.gradleBuild)
          return Build(exitCode, process.output())
        }
  }

  private fun build(vararg args: String) =
      gradle(*args).also { assertEquals(0, it.exitCode, it.output) }

  private fun buildAndFail(vararg args: String, environment: Map<String, String> = emptyMap()) =
      gradle(*args, environment = environment).also { assertNotEquals(0, it.exitCode, it.output) }

  // ---- the Node that builds the Console ----

  @Test
  fun `the Node of the pinned version passes the check`() {
    project()

    val result = build("verifyNode")

    assertEquals("", result.taskOutcome("verifyNode"), result.output)
  }

  @Test
  fun `a Node of another version fails the build and says what is needed and where to read more`() {
    project(nodeVersion = "1.2.3")
    val bin = Files.createTempDirectory("real-node-bin")
    Files.createSymbolicLink(bin.resolve("node"), realNodeBinary())
    Files.createSymbolicLink(bin.resolve("npm"), realNodeBinary().resolveSibling("npm"))

    val result =
        buildAndFail(
            "verifyNode",
            "--no-daemon", // the PATH of the build is the one this process is started with
            environment =
                mapOf(
                    "PATH" to "$bin:/usr/bin:/bin",
                    "JAVA_HOME" to System.getProperty("java.home"),
                ),
        )

    for (expected in
        listOf("1.2.3", actualNodeVersion(), ".node-version", "Node toolchain", "README")) {
      assertTrue(result.output.contains(expected), "'$expected' is missing from:\n${result.output}")
    }
  }

  @Test
  fun `a missing Node fails the build and says what is needed and where to read more`() {
    project()

    val result =
        buildAndFail(
            "verifyNode",
            "--no-daemon", // the PATH of the build is the one this process is started with
            environment =
                mapOf(
                    "PATH" to "/usr/bin:/bin",
                    "JAVA_HOME" to System.getProperty("java.home"),
                ),
        )

    for (expected in listOf(actualNodeVersion(), ".node-version", "Node toolchain", "README")) {
      assertTrue(result.output.contains(expected), "'$expected' is missing from:\n${result.output}")
    }
  }

  // ---- skipping the Console, and the builds that must not ----

  @Test
  fun `skipping the Console does not stop a build that only works on the backend`() {
    project()

    val result = build("shadowJar", "-Prunline.skipConsole=true")

    assertNull(result.taskOutcome("requireConsole"), result.output)
    assertNull(result.taskOutcome("verifyNode"), "no Node is needed: ${result.output}")
  }

  @Test
  fun `skipping the Console is said aloud`() {
    project()

    val result = build("shadowJar", "-Prunline.skipConsole=true")

    assertTrue(result.output.contains("without the Console"), result.output)
  }

  @Test
  fun `the packaged tests refuse a build that skips the Console`() {
    project()

    val result = buildAndFail("packagedTest", "-Prunline.skipConsole=true")

    assertTrue(result.output.contains("runline.skipConsole"), result.output)
    assertTrue(result.output.contains("packagedTest"), result.output)
  }

  @Test
  fun `a release build refuses to skip the Console`() {
    project()

    val result = buildAndFail("shadowJar", "-Prunline.skipConsole=true", "-Prunline.release=true")

    assertTrue(result.output.contains("runline.skipConsole"), result.output)
    assertTrue(result.output.contains("release"), result.output)
  }

  @Test
  fun `the packaged tests and a release build go on when the Console is not skipped`() {
    project()

    build("packagedTest")
    // The release flag alone is not what stops it: here it only has to get past the Console.
    val release = build("requireConsole", "-Prunline.release=true")

    assertEquals("", release.taskOutcome("requireConsole"), release.output)
  }

  // ---- building the Console ----

  /** The real Console project, as the probe's own (without what an earlier build left in it). */
  private fun copyConsole() {
    Files.walk(consoleSources).use { paths ->
      paths
          .filter { path ->
            val relative = consoleSources.relativize(path).toString()
            !relative.startsWith("node_modules") && !relative.startsWith("dist")
          }
          .forEach { path ->
            val target = dir.resolve("console").resolve(consoleSources.relativize(path).toString())
            if (Files.isDirectory(path)) target.createDirectories() else Files.copy(path, target)
          }
    }
  }

  @Test
  fun `the Console is built from the lock file into index html and hashed files`() {
    project()
    copyConsole()

    build("consoleBuild")

    val out = dir.resolve("build/console")
    assertTrue(out.resolve("index.html").exists())
    assertTrue(
        Files.list(out.resolve("assets")).use { files ->
          files.anyMatch { it.toString().endsWith(".js") }
        }
    )
    assertTrue(Files.readString(out.resolve("index.html")).contains("/assets/"))
  }

  @Test
  fun `nothing changed means the Console is not built again, a changed source builds it`() {
    project()
    copyConsole()
    build("consoleBuild")

    val again = build("consoleBuild")
    write("console/src/App.svelte", "<h1>Changed</h1>\n")
    val afterChange = build("consoleBuild")

    for (task in listOf("consoleInstall", "consoleBuild")) {
      assertEquals("UP-TO-DATE", again.taskOutcome(task), again.output)
    }
    assertEquals("UP-TO-DATE", afterChange.taskOutcome("consoleInstall"), afterChange.output)
    assertEquals("", afterChange.taskOutcome("consoleBuild"), afterChange.output)
  }

  @Test
  fun `the checks of the Console are part of check`() {
    project()
    copyConsole()
    write(
        "build.gradle.kts",
        Files.readString(dir.resolve("build.gradle.kts")) + "tasks.register(\"check\")\n",
    )

    val result = build("check")

    assertEquals("", result.taskOutcome("consoleTest"), result.output)
    assertEquals("", result.taskOutcome("consoleTypecheck"), result.output)
  }

  @Test
  fun `a built Console does not hold the address of any environment`() {
    project()
    copyConsole()
    build("consoleBuild")

    val built =
        Files.walk(dir.resolve("build/console")).use { files ->
          files
              .filter { Files.isRegularFile(it) }
              .map { Files.readString(it) }
              .toList()
              .joinToString("\n")
        }

    assertFalse(
        Regex("https?://(?!www\\.w3\\.org|svelte\\.dev|github\\.com)").containsMatchIn(built),
        built.take(2000),
    )
    assertFalse(built.contains("localhost"), "the development proxy is in the build")
    assertFalse(built.contains("RUNLINE_ENGINE_URL"), "the development proxy is in the build")
  }
}
