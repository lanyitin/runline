package dev.lawlan.runline.runner

import dev.lawlan.runline.core.FileScope
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

enum class RunOutcome {
  SUCCEEDED,
  FAILED,
  CANCELLED,
  INTERRUPTED,
}

/**
 * Where and how long run directories live. [sharedRoot] must be persistent storage; [runRoot] may
 * be scratch space.
 */
data class WorkspaceConfig(
    val sharedRoot: Path,
    val runRoot: Path,
    val maxBytesPerScope: Long,
    val failedRunRetention: Duration,
) {
  companion object {
    private const val SHARED_ROOT = "RUNLINE_SHARED_ROOT"
    private const val RUN_ROOT = "RUNLINE_RUN_ROOT"
    private const val MAX_BYTES = "RUNLINE_WORKSPACE_MAX_BYTES"
    private const val RETENTION_SECONDS = "RUNLINE_FAILED_RUN_RETENTION_SECONDS"

    /** Reads the configuration from [env]; a missing or malformed value fails fast. */
    fun fromEnvironment(env: Map<String, String>): WorkspaceConfig =
        WorkspaceConfig(
            sharedRoot = Path.of(required(env, SHARED_ROOT)),
            runRoot = Path.of(required(env, RUN_ROOT)),
            maxBytesPerScope = number(env, MAX_BYTES, min = 1),
            failedRunRetention = Duration.ofSeconds(number(env, RETENTION_SECONDS, min = 0)),
        )

    private fun required(env: Map<String, String>, key: String): String =
        env[key]?.takeIf { it.isNotBlank() } ?: error("Environment variable $key is required")

    private fun number(env: Map<String, String>, key: String, min: Long): Long {
      val value = required(env, key)
      val parsed = value.toLongOrNull()
      check(parsed != null && parsed >= min) {
        "Environment variable $key must be an integer >= $min, was '$value'"
      }
      return parsed
    }
  }
}

data class RunWorkspace(val sharedDir: Path, val runDir: Path, val maxBytesPerScope: Long)

sealed interface WorkspaceEvent {
  data class Created(val scope: FileScope, val pipeline: String, val runId: String?) :
      WorkspaceEvent

  data class Removed(val scope: FileScope, val pipeline: String, val runId: String?) :
      WorkspaceEvent

  data class SharedUsage(val pipeline: String, val bytes: Long) : WorkspaceEvent
}

fun interface WorkspaceObserver {
  fun onEvent(event: WorkspaceEvent)
}

/**
 * Creates, cleans and measures the two kinds of directories a run may use: a shared directory per
 * pipeline (kept across runs) and a private directory per run (removed according to the outcome).
 * Knows nothing about the Engine; callers decide when a run starts and ends.
 */
class Workspaces(
    private val config: WorkspaceConfig,
    private val clock: Clock,
    private val observer: WorkspaceObserver,
) {
  private val active = ConcurrentHashMap.newKeySet<Pair<String, String>>()

  /**
   * Ensures the shared directory exists (creating it when missing), creates the run's private
   * directory and marks the run active. A run id is unique within its pipeline: one whose private
   * directory still exists (running, or retained after a failure) is rejected.
   */
  fun prepare(pipeline: String, runId: String): RunWorkspace {
    val shared = sharedDir(pipeline)
    val run = runDir(pipeline, runId)
    ensure(shared, WorkspaceEvent.Created(FileScope.PIPELINE_SHARED, pipeline, null))
    Files.createDirectories(run.parent)
    try {
      Files.createDirectory(run)
    } catch (e: FileAlreadyExistsException) {
      throw IllegalArgumentException("Run id '$runId' is already used by pipeline '$pipeline'", e)
    }
    observer.onEvent(WorkspaceEvent.Created(FileScope.RUN_PRIVATE, pipeline, runId))
    active += pipeline to runId
    return RunWorkspace(shared, run, config.maxBytesPerScope)
  }

  /**
   * Ends the run. A successful run's private directory is removed at once; otherwise it is kept
   * until the retention period has passed (see [sweep]).
   */
  fun finish(pipeline: String, runId: String, outcome: RunOutcome) {
    val run = runDir(pipeline, runId)
    active -= pipeline to runId
    if (outcome == RunOutcome.SUCCEEDED) {
      removeRunDir(run, pipeline, runId)
    } else if (Files.exists(run)) {
      Files.setLastModifiedTime(run, FileTime.from(clock.instant()))
    }
  }

  /** Removes private directories not in progress whose retention has expired. */
  fun sweep() {
    if (!Files.isDirectory(config.runRoot)) return
    val now = clock.instant()
    for ((pipeline, run) in
        childDirs(config.runRoot).flatMap { p -> childDirs(p).map { p to it } }) {
      val pipelineName = pipeline.fileName.toString()
      val runId = run.fileName.toString()
      if ((pipelineName to runId) in active) continue
      val age = Duration.between(Files.getLastModifiedTime(run).toInstant(), now)
      if (age >= config.failedRunRetention) removeRunDir(run, pipelineName, runId)
    }
  }

  /** Bytes used by the pipeline's shared directory; 0 when it does not exist. */
  fun sharedUsage(pipeline: String): Long {
    val bytes = usage(sharedDir(pipeline))
    observer.onEvent(WorkspaceEvent.SharedUsage(pipeline, bytes))
    return bytes
  }

  /** Empties the pipeline's shared directory; the directory itself stays. */
  fun clearShared(pipeline: String) {
    val shared = sharedDir(pipeline)
    if (!Files.isDirectory(shared)) return
    childPaths(shared).forEach(::deleteTree)
    observer.onEvent(WorkspaceEvent.Removed(FileScope.PIPELINE_SHARED, pipeline, null))
    observer.onEvent(WorkspaceEvent.SharedUsage(pipeline, usage(shared)))
  }

  private fun sharedDir(pipeline: String): Path =
      config.sharedRoot.resolve(safeName("pipeline", pipeline))

  private fun runDir(pipeline: String, runId: String): Path =
      config.runRoot.resolve(safeName("pipeline", pipeline)).resolve(safeName("run id", runId))

  private fun safeName(what: String, name: String): String {
    DirectoryNameRule.violation(what, name)?.let { throw IllegalArgumentException(it) }
    return name
  }

  private fun ensure(dir: Path, created: WorkspaceEvent) {
    if (Files.isDirectory(dir)) return
    Files.createDirectories(dir)
    observer.onEvent(created)
  }

  private fun removeRunDir(run: Path, pipeline: String, runId: String) {
    if (!Files.exists(run, LinkOption.NOFOLLOW_LINKS)) return
    deleteTree(run)
    observer.onEvent(WorkspaceEvent.Removed(FileScope.RUN_PRIVATE, pipeline, runId))
  }

  private fun childPaths(dir: Path): List<Path> = Files.list(dir).use { it.toList() }

  private fun childDirs(dir: Path): List<Path> =
      childPaths(dir).filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }

  private fun usage(dir: Path): Long {
    if (!Files.isDirectory(dir)) return 0L
    return Files.walk(dir).use { s ->
      s.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
          .mapToLong { Files.size(it) }
          .sum()
    }
  }

  /** Deletes [root] and everything below it; symbolic links are removed, never followed. */
  private fun deleteTree(root: Path) {
    Files.walkFileTree(
        root,
        object : SimpleFileVisitor<Path>() {
          override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.delete(file)
            return FileVisitResult.CONTINUE
          }

          override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            if (exc != null) throw exc
            Files.delete(dir)
            return FileVisitResult.CONTINUE
          }
        },
    )
  }
}

/**
 * The one rule for a pipeline name and a run id, which become directory names. [Workspaces] applies
 * it when it makes directories; callers that only want to know whether a name would be accepted ask
 * here, which changes nothing on disk. Each function returns why the value is refused, or null.
 */
object DirectoryNameRule {
  private val NAME = Regex("[A-Za-z0-9._-]+")

  fun pipelineViolation(name: String): String? = violation("pipeline", name)

  fun runIdViolation(runId: String): String? = violation("run id", runId)

  internal fun violation(what: String, name: String): String? =
      if (NAME.matches(name) && name != "." && name != "..") null
      else "Invalid $what '$name': only letters, digits, '.', '_' and '-' are allowed"
}
