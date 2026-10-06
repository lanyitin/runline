package dev.lawlan.runline.devkit

import dev.lawlan.runline.analyzer.AllowList
import dev.lawlan.runline.analyzer.AllowListText
import dev.lawlan.runline.analyzer.DefaultAllowList
import dev.lawlan.runline.runner.RecordingOptions
import dev.lawlan.runline.runner.WorkspaceConfig
import java.nio.file.Path
import java.time.Duration

/** How a recording run is kept and where its output is written. */
data class RecordingConfig(
    val options: RecordingOptions,
    val directory: Path,
    /** The directory as the user wrote it (or the default), for messages: no host path is shown. */
    val shownAs: String,
)

/** Where the allow list used for a verdict came from. */
enum class AllowListSource {
  DEFAULT,
  OVERRIDE,
}

/**
 * Settings of the development entry point, read from environment variables. Without
 * `RUNLINE_ALLOW_LIST` the allow list is the project's default (analyzer's `DefaultAllowList`). The
 * two directory variables keep the names the Engine uses; unlike the Engine, they are optional here
 * and default to places inside the development project.
 */
data class DevConfig(
    val workspace: WorkspaceConfig,
    val allowList: AllowList,
    /** Whether [allowList] is the project's default or what the user set in the environment. */
    val allowListSource: AllowListSource = AllowListSource.DEFAULT,
    /** Whether the verdict output also lists every entry of [allowList]. */
    val showAllowListEntries: Boolean = false,
    /** How long to wait for a run's result before giving up on it. */
    val waitLimit: Duration,
    /** Present when the development entry records the run's IO; null (default) is a normal run. */
    val recording: RecordingConfig? = null,
) {
  val allowListDisplay: AllowListDisplay
    get() = AllowListDisplay(allowListSource, allowList, showAllowListEntries)

  companion object {
    private const val SHARED_ROOT = "RUNLINE_SHARED_ROOT"
    private const val RUN_ROOT = "RUNLINE_RUN_ROOT"
    private const val ALLOW_LIST = "RUNLINE_ALLOW_LIST"
    private const val ALLOW_LIST_VERSION = "RUNLINE_ALLOW_LIST_VERSION"
    private const val SHOW_ALLOW_LIST = "RUNLINE_SHOW_ALLOW_LIST"
    private const val WAIT_SECONDS = "RUNLINE_DEV_WAIT_SECONDS"
    private const val RECORD = "RUNLINE_RECORD"
    private const val RECORD_MAX_EVENTS = "RUNLINE_RECORD_MAX_EVENTS"
    private const val RECORD_DIR = "RUNLINE_RECORD_DIR"

    private const val DEFAULT_MAX_BYTES_PER_SCOPE = 100L * 1024 * 1024
    private val DEFAULT_FAILED_RUN_RETENTION = Duration.ofDays(1)
    private const val DEFAULT_WAIT_SECONDS = 3600L

    /** Reads the settings from [env]; relative paths are taken relative to [projectDir]. */
    fun fromEnvironment(env: Map<String, String>, projectDir: Path): DevConfig =
        DevConfig(
            workspace =
                WorkspaceConfig(
                    sharedRoot =
                        projectDir.resolve(optional(env, SHARED_ROOT) ?: ".runline/shared"),
                    runRoot = projectDir.resolve(optional(env, RUN_ROOT) ?: ".runline/runs"),
                    maxBytesPerScope = DEFAULT_MAX_BYTES_PER_SCOPE,
                    failedRunRetention = DEFAULT_FAILED_RUN_RETENTION,
                ),
            allowList = allowList(env),
            allowListSource =
                if (ALLOW_LIST in env) AllowListSource.OVERRIDE else AllowListSource.DEFAULT,
            showAllowListEntries = showAllowList(env),
            waitLimit = Duration.ofSeconds(waitSeconds(env)),
            recording = recording(env, projectDir),
        )

    /** Recording is off unless `RUNLINE_RECORD` is `true`; the other two variables then tune it. */
    private fun recording(env: Map<String, String>, projectDir: Path): RecordingConfig? {
      when (val value = optional(env, RECORD)?.trim()?.lowercase()) {
        null -> return null
        "true" -> Unit
        else -> error("Environment variable $RECORD must be true when set, was '$value'")
      }
      val directory = optional(env, RECORD_DIR) ?: ".runline/recordings"
      return RecordingConfig(
          RecordingOptions(maxEvents = maxEvents(env)),
          projectDir.resolve(directory),
          directory,
      )
    }

    private fun maxEvents(env: Map<String, String>): Int {
      val value = optional(env, RECORD_MAX_EVENTS) ?: return RecordingOptions.DEFAULT_MAX_EVENTS
      val parsed = value.toIntOrNull()
      check(parsed != null && parsed >= 0) {
        "Environment variable $RECORD_MAX_EVENTS must be an integer >= 0, was '$value'"
      }
      return parsed
    }

    private fun showAllowList(env: Map<String, String>): Boolean =
        when (val value = optional(env, SHOW_ALLOW_LIST)?.trim()?.lowercase()) {
          null -> false
          "true" -> true
          else -> error("Environment variable $SHOW_ALLOW_LIST must be true when set, was '$value'")
        }

    private fun optional(env: Map<String, String>, key: String): String? {
      val value = env[key] ?: return null
      check(value.isNotBlank()) { "Environment variable $key must not be blank" }
      return value
    }

    /**
     * The project's default list unless the variable is set; a variable that is set, even empty,
     * replaces it completely.
     */
    private fun allowList(env: Map<String, String>): AllowList {
      val raw = env[ALLOW_LIST] ?: return DefaultAllowList.allowList()
      val parsed = AllowListText.parse(raw)
      parsed.problems.firstOrNull()?.let {
        error("Environment variable $ALLOW_LIST entry '${it.entry}' is invalid: ${it.reason}")
      }
      val entries = parsed.entries
      val version =
          optional(env, ALLOW_LIST_VERSION) ?: if (entries.isEmpty()) "local-empty" else "local"
      return AllowList(version, entries)
    }

    private fun waitSeconds(env: Map<String, String>): Long {
      val value = optional(env, WAIT_SECONDS) ?: return DEFAULT_WAIT_SECONDS
      val parsed = value.toLongOrNull()
      check(parsed != null && parsed >= 1) {
        "Environment variable $WAIT_SECONDS must be an integer >= 1, was '$value'"
      }
      return parsed
    }
  }
}
