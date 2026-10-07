package dev.lawlan.runline.engine.support

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * One Docker Compose project made of the real files under `deploy/docker` (and the overrides a test
 * adds to them), run with the real `docker compose`. [envFile] is what an operator's `.env` would
 * be; the test never relies on a file of that name. The Docker host is the one the environment says
 * (`DOCKER_HOST`), so a test needs Docker and fails without it, it is never skipped.
 */
class ComposeProject(
    private val work: Path,
    private val project: String,
    private val envFile: Path,
    private val files: List<Path>,
) {
  /** What a `docker` command ended with. */
  data class Result(val exitCode: Int, val output: String)

  private fun start(command: List<String>): Process {
    val builder = ProcessBuilder(command).redirectErrorStream(true).directory(work.toFile())
    // The Dockerfile is outside the build context (as the real file says); the build is done by
    // Docker without the extra permission prompt of bake for reading it.
    builder.environment()["COMPOSE_BAKE"] = "false"
    builder.environment()["BUILDX_BAKE_ENTITLEMENTS_FS"] = "0"
    return builder.start()
  }

  /** Runs [command] and returns how it ended; fails if it does not end within the time. */
  fun execute(command: List<String>, timeoutMinutes: Long = 10): Result {
    val process = start(command)
    val output = StringBuilder()
    val reader = Thread { output.append(process.inputStream.readAllBytes().decodeToString()) }
    reader.start()
    if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
      process.destroyForcibly()
      fail("${command.joinToString(" ")} did not finish within $timeoutMinutes minutes:\n$output")
    }
    reader.join()
    return Result(process.exitValue(), output.toString())
  }

  private fun composeCommand(args: Array<out String>) =
      listOf("docker", "compose", "-p", project, "--env-file", envFile.toString()) +
          files.flatMap { listOf("-f", it.toString()) } +
          args

  /** `docker compose [args]`, whatever the exit code. */
  fun tryCompose(vararg args: String, timeoutMinutes: Long = 10): Result =
      execute(composeCommand(args), timeoutMinutes)

  /** `docker compose [args]`; fails unless it ends with 0. */
  fun compose(vararg args: String, timeoutMinutes: Long = 10): String {
    val command = composeCommand(args)
    val result = execute(command, timeoutMinutes)
    assertEquals(0, result.exitCode, "${command.joinToString(" ")}:\n${result.output}")
    return result.output
  }

  /** `docker [args]` (no compose), for the container and image of this project. */
  fun docker(vararg args: String, timeoutMinutes: Long = 10): Result =
      execute(listOf("docker") + args, timeoutMinutes)

  /** The id of the container of [service]. */
  fun containerId(service: String): String = compose("ps", "-a", "-q", service).trim()

  /** A command in the running container of [service]; returns how it ended. */
  fun exec(service: String, vararg command: String): Result =
      tryCompose("exec", "-T", service, *command)

  /** Takes the project down with its volumes; never fails the test. */
  fun tearDown() {
    runCatching { compose("down", "-v", "--remove-orphans", timeoutMinutes = 3) }
  }
}
