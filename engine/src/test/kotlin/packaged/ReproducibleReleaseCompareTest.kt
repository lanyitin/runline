package dev.lawlan.runline.engine.packaged

import dev.lawlan.runline.accessors.support.TestDirectories
import dev.lawlan.runline.engine.support.ManagedProcess
import dev.lawlan.runline.engine.support.TestTimeouts
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.*

/**
 * The comparison of two release builds (WI-32, ADR-016), checked by really running Gradle (the
 * project's own wrapper, as a process) on a project that applies the script the Engine's build
 * applies, with two directories of real files where the two builds' output goes.
 */
class ReproducibleReleaseCompareTest {
  private val script = Path.of(System.getProperty("runline.reproducibleReleaseScript"))
  private val gradlew = System.getProperty("runline.gradlew")
  private val dir: Path = TestDirectories.forThisTest("reproducible-release-project")
  private val logs: Path = TestDirectories.forThisTest("reproducible-release-logs")
  private var builds = 0

  init {
    dir.resolve("settings.gradle.kts").writeText("rootProject.name = \"probe\"\n")
    dir.resolve("build.gradle.kts")
        .writeText("apply(from = \"${script.toString().replace("\\", "/")}\")\n")
  }

  private fun put(build: String, name: String, content: String) {
    val file = dir.resolve("build/reproducibility/$build").resolve(name)
    Files.createDirectories(file.parent)
    file.writeBytes(content.toByteArray())
  }

  private fun sha256(content: String): String =
      MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).joinToString("") {
        "%02x".format(it)
      }

  private class Result(val exitCode: Int, val output: String)

  private fun compare(): Result {
    val log = logs.resolve("gradle-${builds++}.log")
    ManagedProcess.start(
            "Gradle on the probe project",
            listOf(gradlew, "-p", dir.toString(), "--console=plain", "compareReleaseBuilds"),
            log,
        )
        .use { process ->
          return Result(process.awaitExit(TestTimeouts.gradleBuild), process.output())
        }
  }

  @Test
  fun `two builds with the same bytes pass and the report holds the hash of every jar`() {
    for (build in listOf("first", "second")) {
      put(build, "engine.jar", "engine")
      put(build, "run-runtime/core.jar", "core")
    }

    val result = compare()

    assertEquals(0, result.exitCode, result.output)
    assertTrue(result.output.contains("${sha256("engine")}  engine.jar"), result.output)
    assertTrue(result.output.contains("${sha256("core")}  run-runtime/core.jar"), result.output)
  }

  @Test
  fun `a jar that differs fails the comparison and is named with both hashes`() {
    put("first", "engine.jar", "engine")
    put("second", "engine.jar", "engine")
    put("first", "run-runtime/core.jar", "core one")
    put("second", "run-runtime/core.jar", "core two")

    val result = compare()

    assertNotEquals(0, result.exitCode, result.output)
    assertTrue(result.output.contains("run-runtime/core.jar"), result.output)
    assertTrue(result.output.contains(sha256("core one")), result.output)
    assertTrue(result.output.contains(sha256("core two")), result.output)
    assertFalse(result.output.contains("differs: engine.jar"), result.output)
  }

  @Test
  fun `a jar that only one build made fails the comparison and is named`() {
    put("first", "engine.jar", "engine")
    put("second", "engine.jar", "engine")
    put("second", "run-runtime/extra.jar", "extra")

    val result = compare()

    assertNotEquals(0, result.exitCode, result.output)
    assertTrue(result.output.contains("run-runtime/extra.jar"), result.output)
  }

  @Test
  fun `builds without any jar fail instead of passing for nothing`() {
    Files.createDirectories(dir.resolve("build/reproducibility/first"))
    Files.createDirectories(dir.resolve("build/reproducibility/second"))

    val result = compare()

    assertNotEquals(0, result.exitCode, result.output)
  }
}
