// Builds the Console (WI-31, ADR-015): a Svelte single page application in `console/`, built by
// Vite with Node into static files that go into the jar. Node is a tool of the build only.
//
// Applied by the Engine's build (`apply(from = ...)`); a project of its own can apply it as well,
// which is how the build tests check it against real projects. It registers:
//
// - `verifyNode`: the Node on the PATH is the one pinned in `.node-version`, or the build fails
//   and says where to read how to get it (README, "Node toolchain");
// - `consoleInstall` (`npm ci`: from the lock file, nothing resolved or downloaded at run time),
//   `consoleTypecheck`, `consoleTest` and `consoleBuild`, each redone only when its inputs change;
// - `consoleCheck`, which `check` depends on: the Console's type check and tests;
// - `requireConsole`: fails when the Console is skipped. `packagedTest` always depends on it, and
//   `shadowJar` when the build is a release (`-Prunline.release=true`, see build-info).
//
// `-Prunline.skipConsole=true` leaves the Console out, for working on the backend on this machine
// only: no Node is needed then, and the Engine's `/` answers 404. The files of the build are in
// the extra property `consoleAssets` (empty when skipped), for the jar's resources.

val consoleDir: File = rootProject.file("console")
val nodeVersionFile: File = rootProject.file(".node-version")
val consoleOutput: Provider<Directory> = layout.buildDirectory.dir("console")
val consoleStamps: Provider<Directory> = layout.buildDirectory.dir("console-stamps")

val skipConsole: Boolean =
    providers.gradleProperty("runline.skipConsole").map { it.toBoolean() }.orElse(false).get()
val releaseBuild: Boolean =
    providers.gradleProperty("runline.release").map { it.toBoolean() }.orElse(false).get()

if (skipConsole) {
  logger.warn(
      "Console: skipped (runline.skipConsole). This Engine is built without the Console: its / " +
          "answers 404. Only for working on the backend on this machine."
  )
}

abstract class VerifyNode : DefaultTask() {
  @get:InputFile abstract val versionFile: RegularFileProperty

  @TaskAction
  fun verify() {
    val required = versionFile.get().asFile.readText().trim().removePrefix("v")
    val hint =
        "The Console is built with Node $required, pinned in .node-version; the 'Node toolchain' " +
            "section of README.md says how to get it (devcontainer, or mise). If Node is " +
            "installed but is not found, stop the Gradle daemon (./gradlew --stop) so that it " +
            "sees the PATH of this shell."
    val found = answerOf("node", "--version")
    if (found == null || found.first != 0) {
      throw GradleException(
          "Node is missing or does not run" +
              (found?.second?.let { " (it said: $it)" } ?: "") +
              ". $hint"
      )
    }
    val version = found.second.removePrefix("v")
    if (version != required) {
      throw GradleException("Node $version is installed, not the version $required. $hint")
    }
    val npm = answerOf("npm", "--version")
    if (npm == null || npm.first != 0) {
      throw GradleException(
          "npm is missing or does not run" +
              (npm?.second?.let { " (it said: $it)" } ?: "") +
              ". $hint"
      )
    }
  }

  /** The exit code and output of the command, or null if it could not be started. */
  private fun answerOf(vararg command: String): Pair<Int, String>? =
      try {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText().trim()
        process.waitFor() to text
      } catch (e: java.io.IOException) {
        null
      }
}

fun sources(): FileTree = fileTree(consoleDir) { exclude("node_modules/**", "dist/**") }

val verifyNode =
    tasks.register<VerifyNode>("verifyNode") {
      group = "console"
      description = "Checks that the Node on the PATH is the one pinned in .node-version"
      versionFile = nodeVersionFile
      enabled = !skipConsole
    }

val installStamp: Provider<RegularFile> = consoleStamps.map { it.file("install.stamp") }

val consoleInstall =
    tasks.register<Exec>("consoleInstall") {
      group = "console"
      description = "Installs the Console's dependencies from the lock file (npm ci)"
      dependsOn(verifyNode)
      enabled = !skipConsole
      workingDir = consoleDir
      commandLine("npm", "ci")
      inputs.file(consoleDir.resolve("package.json")).withPropertyName("packageJson")
      inputs.file(consoleDir.resolve("package-lock.json")).withPropertyName("lockFile")
      inputs.file(consoleDir.resolve(".npmrc")).withPropertyName("npmrc").optional()
      inputs.file(nodeVersionFile).withPropertyName("nodeVersion")
      outputs.file(installStamp)
      // The stamp says what was installed; the installed files themselves have to be there too.
      outputs.upToDateWhen { consoleDir.resolve("node_modules").isDirectory }
      doLast { installStamp.get().asFile.apply { parentFile.mkdirs() }.writeText("installed\n") }
    }

/** An npm script of the Console that has to pass; redone only when the Console's files change. */
fun npmCheck(name: String, script: String, what: String) =
    tasks.register<Exec>(name) {
      group = "console"
      description = what
      dependsOn(consoleInstall)
      enabled = !skipConsole
      workingDir = consoleDir
      commandLine("npm", "run", script)
      inputs
          .files(sources())
          .withPropertyName("sources")
          .withPathSensitivity(PathSensitivity.RELATIVE)
      inputs.file(installStamp).withPropertyName("installed")
      val stamp = consoleStamps.map { it.file("$name.stamp") }
      outputs.file(stamp)
      doLast { stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("passed\n") }
    }

val consoleTypecheck = npmCheck("consoleTypecheck", "typecheck", "Type-checks the Console")
val consoleTest = npmCheck("consoleTest", "test", "Runs the Console's tests")

val consoleCheck =
    tasks.register("consoleCheck") {
      group = "verification"
      description = "The Console's type check and tests"
      dependsOn(consoleTypecheck, consoleTest)
    }

val consoleBuild =
    tasks.register<Exec>("consoleBuild") {
      group = "console"
      description = "Builds the Console into static files (Vite)"
      dependsOn(consoleInstall)
      enabled = !skipConsole
      workingDir = consoleDir
      inputs
          .files(sources().matching { exclude("**/*.test.ts") })
          .withPropertyName("sources")
          .withPathSensitivity(PathSensitivity.RELATIVE)
      inputs.file(installStamp).withPropertyName("installed")
      outputs.dir(consoleOutput)
      // Resolved when the task runs, so that the output directory is the one of this build.
      doFirst {
        commandLine(
            "npm",
            "run",
            "build",
            "--",
            "--outDir",
            consoleOutput.get().asFile.absolutePath,
            "--emptyOutDir",
        )
      }
    }

val requireConsole =
    tasks.register("requireConsole") {
      group = "console"
      description = "Fails when the Console is skipped: packagedTest and releases must contain it"
      doLast {
        if (skipConsole) {
          throw GradleException(
              "-Prunline.skipConsole=true builds an Engine without the Console, for working on " +
                  "the backend on this machine only. packagedTest and a release build " +
                  "(-Prunline.release=true) must contain the Console: remove the property."
          )
        }
      }
    }

tasks.matching { it.name == "check" }.configureEach { dependsOn(consoleCheck) }

tasks.matching { it.name == "packagedTest" }.configureEach { dependsOn(requireConsole) }

if (releaseBuild) {
  tasks.matching { it.name == "shadowJar" }.configureEach { dependsOn(requireConsole) }
}

extra["consoleAssets"] = if (skipConsole) files() else files(consoleBuild)
