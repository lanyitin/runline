// Verifies that a release build is byte-reproducible (WI-32, ADR-016).
//
// Applied by the Engine's build (`apply(from = ...)`); a project of its own can apply it as well,
// which is how the build tests check the comparison against real files. It registers:
//
// - `compareReleaseBuilds`: compares every jar under `build/reproducibility/first` with the one of
//   the same path under `build/reproducibility/second` by SHA-256, prints the hash of each, and
//   fails naming every jar that differs or that only one of the builds made;
// - `verifyReproducibleRelease`: the single entry. It makes the fixed build platform (the image
//   of `release/Dockerfile`, linux/amd64, pinned by digest), has it build HEAD twice from a fresh
//   clone (`release/reproduce.sh`: release flag, nothing of the first build left for the second)
//   and then runs `compareReleaseBuilds`. Not part of `check`: it needs Docker and takes long.
//   README, "Reproducible release builds".

abstract class CompareReleaseBuilds : DefaultTask() {
  @get:InputDirectory abstract val first: DirectoryProperty

  @get:InputDirectory abstract val second: DirectoryProperty

  @TaskAction
  fun compare() {
    val a = hashes(first.get().asFile)
    val b = hashes(second.get().asFile)
    if (a.isEmpty() && b.isEmpty()) {
      throw GradleException("Neither build made a jar: nothing was compared.")
    }
    val problems = mutableListOf<String>()
    for (name in (a.keys + b.keys).toSortedSet()) {
      val x = a[name]
      val y = b[name]
      when {
        x == null -> problems += "only the second build made $name ($y)"
        y == null -> problems += "only the first build made $name ($x)"
        x != y -> problems += "differs: $name\n    first:  $x\n    second: $y"
      }
      if (x != null && x == y) logger.lifecycle("$x  $name")
    }
    if (problems.isNotEmpty()) {
      throw GradleException(
          "The two release builds are not byte-identical:\n  " + problems.joinToString("\n  ")
      )
    }
    logger.lifecycle("Reproducible: ${a.size} jars, identical in both builds.")
  }

  /** The SHA-256 of every jar below the directory, by its path relative to it. */
  private fun hashes(dir: File): Map<String, String> =
      dir.walkTopDown()
          .filter { it.isFile && it.extension == "jar" }
          .associate { file ->
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
              val buffer = ByteArray(65536)
              while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
              }
            }
            file.relativeTo(dir).invariantSeparatorsPath to
                digest.digest().joinToString("") { "%02x".format(it) }
          }
}

val reproducibility: Provider<Directory> = layout.buildDirectory.dir("reproducibility")

val compareReleaseBuilds =
    tasks.register<CompareReleaseBuilds>("compareReleaseBuilds") {
      group = "verification"
      description = "Compares the jars of two release builds by SHA-256 and names those that differ"
      first = reproducibility.map { it.dir("first") }
      second = reproducibility.map { it.dir("second") }
      // Always compares what is there now.
      outputs.upToDateWhen { false }
    }

// Only the one platform the guarantee is given for (README, "Reproducible release builds").
val releasePlatform = "linux/amd64"
// Read only when a task runs: a project that applies this script for the comparison alone has no
// such file.
val nodeVersion: Provider<String> =
    providers
        .fileContents(rootProject.layout.projectDirectory.file(".node-version"))
        .asText
        .map { it.trim() }
val releaseImage: Provider<String> = nodeVersion.map { "runline-release-platform:$it" }

val releaseBundle = reproducibility.map { it.file("source.bundle") }

val checkCleanTree =
    tasks.register<Exec>("checkCleanTreeForReproducibility") {
      group = "verification"
      description = "Fails when the working tree has changes: HEAD is what gets verified"
      workingDir = rootProject.projectDir
      commandLine("git", "status", "--porcelain")
      standardOutput = java.io.ByteArrayOutputStream()
      doLast {
        val changes = standardOutput.toString().trim()
        if (changes.isNotEmpty()) {
          throw GradleException(
              "The working tree has changes; the verification builds the commit HEAD, so it " +
                  "would say nothing of them. Commit or stash them first:\n$changes"
          )
        }
      }
    }

val packSource =
    tasks.register<Exec>("packReleaseSource") {
      group = "verification"
      description = "Packs HEAD as a git bundle for the build platform to clone"
      dependsOn(checkCleanTree)
      workingDir = rootProject.projectDir
      doFirst { releaseBundle.get().asFile.parentFile.mkdirs() }
      commandLine(
          "git",
          "bundle",
          "create",
          "--quiet",
          releaseBundle.get().asFile.absolutePath,
          "HEAD",
      )
    }

val buildPlatform =
    tasks.register<Exec>("buildReleasePlatform") {
      group = "verification"
      description = "Builds the fixed build platform image (release/Dockerfile, $releasePlatform)"
      workingDir = rootProject.projectDir
      doFirst {
        commandLine(
            "docker",
            "build",
            "--platform",
            releasePlatform,
            "--build-arg",
            "NODE_VERSION=${nodeVersion.get()}",
            "--tag",
            releaseImage.get(),
            "--file",
            "release/Dockerfile",
            "release",
        )
      }
    }

val runBuilds =
    tasks.register<Exec>("runReleaseBuilds") {
      group = "verification"
      description = "Builds HEAD twice, from clean state, on the fixed build platform"
      dependsOn(packSource, buildPlatform)
      workingDir = rootProject.projectDir
      doFirst {
        delete(reproducibility.get().dir("first"), reproducibility.get().dir("second"))
        reproducibility.get().asFile.mkdirs()
        commandLine(
            "docker",
            "run",
            "--rm",
            "--platform",
            releasePlatform,
            "--volume",
            "${reproducibility.get().asFile.absolutePath}:/out",
            "--volume",
            "${rootProject.file("release/reproduce.sh").absolutePath}:/release/reproduce.sh:ro",
            releaseImage.get(),
            "sh",
            "/release/reproduce.sh",
        )
      }
    }

// After the builds, when both are asked for; not at all when they failed.
compareReleaseBuilds.configure { mustRunAfter(runBuilds) }

tasks.register("verifyReproducibleRelease") {
  group = "verification"
  description =
      "Builds HEAD twice on the fixed build platform (Docker) and compares every jar by SHA-256"
  dependsOn(runBuilds, compareReleaseBuilds)
}
