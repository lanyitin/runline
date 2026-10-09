package dev.lawlan.runline.engine.packaged

import dev.lawlan.runline.accessors.support.TestDirectories
import dev.lawlan.runline.engine.support.ManagedProcess
import dev.lawlan.runline.engine.support.TestTimeouts
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Properties
import kotlin.io.path.writeText
import kotlin.test.*

/**
 * The build info the build writes into the jar (WI-28, ADR-016), checked by really building: each
 * test makes a project in a temporary directory, with a real git repository when it needs one, and
 * runs Gradle (the project's own wrapper, as a process) on it with the same script the Engine's
 * build applies.
 */
class BuildInfoBuildTest {
  private val script = Path.of(System.getProperty("runline.buildInfoScript"))
  private val gradlew = System.getProperty("runline.gradlew")
  private val dir: Path = TestDirectories.forThisTest("build-info-project")
  // Outside the project: a log in it would be an untracked file, and make every tree dirty.
  private val logs: Path = TestDirectories.forThisTest("build-info-logs")
  private val commitDate = "2026-03-04T05:06:07+09:00"

  private fun write(name: String, text: String) {
    val file = dir.resolve(name)
    Files.createDirectories(file.parent)
    file.writeText(text)
  }

  private fun project() {
    write("settings.gradle.kts", "rootProject.name = \"probe\"\n")
    write("build.gradle.kts", "version = \"9.9.9\"\napply(from = \"$script\")\n")
    write(".gitignore", "build/\n.gradle/\n.kotlin/\n")
  }

  private fun git(vararg args: String): String {
    val process =
        ProcessBuilder(listOf("git") + args)
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .apply {
              environment().apply {
                put("GIT_AUTHOR_DATE", commitDate)
                put("GIT_COMMITTER_DATE", commitDate)
                put("GIT_AUTHOR_NAME", "t")
                put("GIT_AUTHOR_EMAIL", "t@example.com")
                put("GIT_COMMITTER_NAME", "t")
                put("GIT_COMMITTER_EMAIL", "t@example.com")
              }
            }
            .start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { "git ${args.toList()} failed: $output" }
    return output.trim()
  }

  private fun repository() {
    project()
    git("init", "-q")
    git("add", ".")
    git("commit", "-q", "-m", "first")
  }

  private class Build(val exitCode: Int, val output: String) {
    /** Whether `generateBuildInfo` was skipped as up to date, or really ran. */
    val generateBuildInfoUpToDate: Boolean
      get() =
          output
              .lines()
              .single { it.startsWith("> Task :generateBuildInfo") }
              .endsWith("UP-TO-DATE")
  }

  private var builds = 0

  private fun gradle(vararg args: String): Build {
    val log = logs.resolve("gradle-${builds++}.log")
    ManagedProcess.start(
            "Gradle on the probe project",
            listOf(gradlew, "-p", dir.toString(), "--console=plain", "generateBuildInfo") + args,
            log,
        )
        .use { process ->
          val exitCode = process.awaitExit(TestTimeouts.gradleBuild)
          return Build(exitCode, process.output())
        }
  }

  private fun build(vararg args: String): Build =
      gradle(*args).also { assertEquals(0, it.exitCode, it.output) }

  private fun buildAndFail(vararg args: String): Build =
      gradle(*args).also { assertNotEquals(0, it.exitCode, it.output) }

  private fun buildInfo(): Properties =
      Properties().apply {
        Files.newBufferedReader(
                dir.resolve("build/generated/build-info/runline-build-info.properties")
            )
            .use { load(it) }
      }

  @Test
  fun `a clean working tree gives the hash, the commit time in UTC, the version and not dirty`() {
    repository()

    build()

    val info = buildInfo()
    assertEquals(git("rev-parse", "HEAD"), info["commitHash"])
    assertEquals("false", info["dirty"])
    assertEquals(Instant.parse("2026-03-03T20:06:07Z"), Instant.parse(info["buildTime"] as String))
    assertEquals("9.9.9", info["version"])
  }

  @Test
  fun `the build info holds the four values and nothing of the machine or the time of the build`() {
    repository()

    build()

    assertEquals(
        setOf("version", "commitHash", "dirty", "buildTime"),
        buildInfo().stringPropertyNames(),
    )
    val text =
        Files.readString(dir.resolve("build/generated/build-info/runline-build-info.properties"))
    assertFalse(text.contains(dir.toString()), text)
    assertFalse(text.contains(System.getProperty("user.name")), text)
    assertFalse(text.startsWith("#"), "no date comment: $text")
  }

  @Test
  fun `the same commit built again gives the same bytes`() {
    repository()
    build()
    val file = dir.resolve("build/generated/build-info/runline-build-info.properties")
    val first = Files.readAllBytes(file)

    Files.delete(file)
    build()

    assertContentEquals(first, Files.readAllBytes(file))
  }

  @Test
  fun `a changed tracked file makes the tree dirty and the hash stays HEAD`() {
    repository()
    write("tracked.txt", "one\n")
    git("add", "tracked.txt")
    git("commit", "-q", "-m", "second")
    write("tracked.txt", "two\n")

    build()

    assertEquals("true", buildInfo()["dirty"])
    assertEquals(git("rev-parse", "HEAD"), buildInfo()["commitHash"])
  }

  @Test
  fun `an untracked file that is not ignored makes the tree dirty`() {
    repository()
    write("new.txt", "x\n")

    build()

    assertEquals("true", buildInfo()["dirty"])
  }

  @Test
  fun `an ignored file, such as the build output itself, does not make the tree dirty`() {
    repository()
    write("build/something.bin", "x\n")

    build()
    build() // the first run wrote the build info under build/, ignored

    assertEquals("false", buildInfo()["dirty"])
  }

  @Test
  fun `without a repository the hash is unknown, dirty is true and the time is a constant`() {
    project()

    build()

    assertEquals("unknown", buildInfo()["commitHash"])
    assertEquals("true", buildInfo()["dirty"])
    assertEquals(
        Instant.parse("1970-01-01T00:00:00Z"),
        Instant.parse(buildInfo()["buildTime"] as String),
    )
  }

  @Test
  fun `a repository without a commit is as unknown as no repository`() {
    project()
    git("init", "-q")

    build()

    assertEquals("unknown", buildInfo()["commitHash"])
    assertEquals("true", buildInfo()["dirty"])
  }

  @Test
  fun `the release flag fails a dirty build and says why`() {
    repository()
    write("new.txt", "x\n")

    val result = buildAndFail("-Prunline.release=true")

    assertTrue(result.output.contains("dirty"), result.output)
  }

  @Test
  fun `the release flag fails an unknown commit and says why`() {
    project()

    val result = buildAndFail("-Prunline.release=true")

    assertTrue(result.output.contains("unknown"), result.output)
  }

  @Test
  fun `the release flag lets a clean build with a commit through`() {
    repository()

    val result = build("-Prunline.release=true")

    assertFalse(result.generateBuildInfoUpToDate, result.output)
  }

  @Test
  fun `an ordinary build is not affected by being dirty`() {
    repository()
    write("new.txt", "x\n")

    val result = build()

    assertFalse(result.generateBuildInfoUpToDate, result.output)
  }

  @Test
  fun `nothing changed means the build info is up to date for what follows`() {
    repository()
    build()

    val again = build()

    assertTrue(again.generateBuildInfoUpToDate, again.output)
  }

  @Test
  fun `a dirty tree edited again is still the same state`() {
    repository()
    write("new.txt", "x\n")
    build()
    write("new.txt", "y\n")

    val again = build()

    assertTrue(again.generateBuildInfoUpToDate, again.output)
  }

  @Test
  fun `a new commit or a change of cleanliness produces new build info`() {
    repository()
    build()
    write("new.txt", "x\n")
    assertFalse(build().generateBuildInfoUpToDate)
    git("add", ".")
    git("commit", "-q", "-m", "third")

    val afterCommit = build()

    assertFalse(afterCommit.generateBuildInfoUpToDate, afterCommit.output)
    assertEquals(git("rev-parse", "HEAD"), buildInfo()["commitHash"])
    assertEquals("false", buildInfo()["dirty"])
  }
}
