// Writes what a build knows about itself into a resource of the jar (WI-28, ADR-016): the version,
// the full hash of HEAD, whether the working tree has changes, and the time of HEAD's commit. The
// clock, the host, the user and every path stay out, so the same commit gives the same bytes.
//
// Applied by the Engine's build (`apply(from = ...)`); a project of its own can apply it as well,
// which is how the build tests check it against real git repositories. It registers the task
// `generateBuildInfo`; its output directory is meant to be added to the jar's resources.
//
// `-Prunline.release=true` marks a release build: a hash that is `unknown` or a dirty working tree
// then fails the build, so nothing that cannot be traced to a commit is deployed. An ordinary
// build is not affected.

abstract class GenerateBuildInfo : DefaultTask() {
  @get:Input abstract val version: Property<String>

  @get:Input abstract val commitHash: Property<String>

  @get:Input abstract val dirty: Property<Boolean>

  @get:Input abstract val buildTime: Property<String>

  @get:Input abstract val release: Property<Boolean>

  @get:OutputFile abstract val output: RegularFileProperty

  @TaskAction
  fun generate() {
    if (release.get()) {
      val problems = mutableListOf<String>()
      if (commitHash.get() == "unknown") {
        problems += "the commit hash is unknown (no git repository, or no commit yet)"
      }
      if (dirty.get()) {
        problems +=
            "the working tree is dirty (uncommitted changes, or untracked files that are not ignored)"
      }
      if (problems.isNotEmpty()) {
        throw GradleException(
            "Release build refused: " +
                problems.joinToString("; ") +
                ". A release must be built from a clean checkout of a commit."
        )
      }
    }
    val text =
        listOf(
                "version=${version.get()}",
                "commitHash=${commitHash.get()}",
                "dirty=${dirty.get()}",
                "buildTime=${buildTime.get()}",
            )
            .joinToString("\n", postfix = "\n")
    output.get().asFile.apply {
      parentFile.mkdirs()
      writeText(text)
    }
  }
}

// What git says, asked at every build: cheap, and the task's inputs make sure that nothing
// downstream is redone when the answers are the same as the last time. When git cannot answer (not
// a repository, no commit yet, not installed) the answer is [GIT_FAILED], never an empty text: for
// `git status` an empty answer means a clean tree.
val GIT_FAILED = "?"

fun git(vararg args: String): Provider<String> =
    providers
        .exec {
          commandLine(listOf("git") + args)
          workingDir = project.rootDir
          isIgnoreExitValue = true
        }
        .let { result ->
          providers.provider {
            try {
              if (result.result.get().exitValue == 0) {
                result.standardOutput.asText.get().trim()
              } else {
                GIT_FAILED
              }
            } catch (e: Exception) {
              GIT_FAILED
            }
          }
        }

val head = git("rev-parse", "HEAD").map { if (it.matches(Regex("[0-9a-f]{40}"))) it else "unknown" }
val commitSeconds = git("log", "-1", "--format=%ct", "HEAD")
val changes = git("status", "--porcelain")

tasks.register<GenerateBuildInfo>("generateBuildInfo") {
  group = "build"
  description = "Writes the version, commit hash, dirty flag and commit time for the jar"
  version = project.version.toString()
  commitHash = head
  // Without a hash nothing says the tree is clean.
  dirty = head.zip(changes) { h, c -> h == "unknown" || c.isNotEmpty() }
  buildTime =
      head.zip(commitSeconds) { h, s ->
        val seconds = s.toLongOrNull()
        if (h == "unknown" || seconds == null) "1970-01-01T00:00:00Z"
        else java.time.Instant.ofEpochSecond(seconds).toString()
      }
  release = providers.gradleProperty("runline.release").map { it.toBoolean() }.orElse(false)
  output = layout.buildDirectory.file("generated/build-info/runline-build-info.properties")
}
