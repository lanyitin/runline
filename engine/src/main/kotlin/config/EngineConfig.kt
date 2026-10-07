package dev.lawlan.runline.engine.config

import dev.lawlan.runline.analyzer.AllowListEntry
import dev.lawlan.runline.analyzer.AllowListText
import dev.lawlan.runline.analyzer.DefaultAllowList
import dev.lawlan.runline.engine.artifact.JarLimits
import dev.lawlan.runline.engine.auth.Role
import dev.lawlan.runline.runner.WorkspaceConfig
import io.ktor.server.config.*
import java.nio.file.Path
import java.time.Duration

/** Thrown at startup when configuration is missing or invalid. Never contains a secret value. */
class ConfigurationException(message: String) : RuntimeException(message)

data class DatabaseConfig(val url: String, val user: String, val password: String) {
  // The password must never reach a log line through an accidental toString().
  override fun toString() = "DatabaseConfig(url=$url, user=$user)"

  companion object {
    /** Parses the `postgres.*` settings alone; used by the one-off migration process too. */
    fun from(config: ApplicationConfig): DatabaseConfig {
      val problems = mutableListOf<String>()
      val database = read(config, problems)
      if (problems.isNotEmpty()) throw ConfigurationException(invalid(problems))
      return database
    }

    internal fun read(config: ApplicationConfig, problems: MutableList<String>): DatabaseConfig {
      fun required(key: String): String =
          config.propertyOrNull(key)?.getString()?.trim()?.takeIf { it.isNotEmpty() }
              ?: "".also { problems += "$key is required" }
      return DatabaseConfig(
          required("postgres.url"),
          required("postgres.user"),
          required("postgres.password"),
      )
    }
  }
}

private fun invalid(problems: List<String>) =
    "Invalid configuration: " + problems.joinToString("; ")

/** One configured API token: who it identifies, in which role, and the secret itself. */
data class ApiToken(val name: String, val role: Role, val secret: String) {
  // The secret must never reach a log line through an accidental toString().
  override fun toString() = "ApiToken(name=$name, role=$role)"
}

/** What an upload may be: its size as sent, and what it may expand to (see JarExpansionGuard). */
data class UploadConfig(val maxBytes: Long, val jarLimits: JarLimits)

/**
 * The content the allow list gets the first time the Engine starts (WI-10): [entries], and whether
 * they come from configuration or are the project's default list. Afterwards the allow list lives
 * in the database and is changed through the admin API; this is not read again.
 */
data class InitialAllowList(val entries: List<AllowListEntry>, val fromConfiguration: Boolean)

/** How the Engine names itself in logs, traces and metrics. */
data class TelemetryConfig(val serviceName: String)

/** Where run directories live and how they are kept (WI-11), plus how often they are swept. */
data class WorkspaceSettings(val config: WorkspaceConfig, val sweepInterval: Duration)

/** How runs are executed (WI-08). */
data class RunSettings(
    /** At most this many runs execute at once; the rest wait their turn. */
    val maxConcurrent: Int,
    /** Where the jars every run's class loader gets are (Runner, core, Kotlin library). */
    val runtimeDir: Path,
    /** Cooperative limit on a pipeline body; none when null. */
    val timeout: Duration?,
    /** How long a shutdown waits for runs it has asked to stop. */
    val shutdownGrace: Duration,
    /** How long a run waits for its shared resources before it fails (ADR-007). */
    val resourceWaitTimeout: Duration,
)

/** Where the files of `file` shared resources live (ADR-019); every such path is inside it. */
data class ResourceSettings(val root: Path)

/** How long run, log and trigger records are kept, and how the clean-up runs (WI-20). */
data class RetentionSettings(
    /** A run that has ended is removed, with its log, this long after it ended. */
    val runRetention: Duration,
    /**
     * The log of a run that has ended is removed this long after it ended; not above
     * [runRetention].
     */
    val logRetention: Duration,
    /**
     * Webhook firings are kept this long after they happened: this is the delivery dedup window.
     */
    val webhookFiringRetention: Duration,
    /** Cron firings are kept this long after they happened. */
    val cronFiringRetention: Duration,
    /** How often the clean-up runs. */
    val interval: Duration,
    /** Most rows one statement of the clean-up removes. */
    val batchSize: Int,
) {
  companion object {
    const val DEFAULT_RUN_SECONDS = 30L * 24 * 3600
    const val DEFAULT_WEBHOOK_DEDUP_WINDOW_SECONDS = 7L * 24 * 3600
    const val DEFAULT_CRON_FIRING_SECONDS = 30L * 24 * 3600
    const val DEFAULT_INTERVAL_SECONDS = 3600L
    const val DEFAULT_BATCH_SIZE = 1000L

    /**
     * A caller that is told "not accepted" may send the delivery again for as long as its own retry
     * schedule lasts; common senders keep trying for up to a day or more. A window shorter than a
     * day could let a late retry through as a new delivery.
     */
    const val MIN_WEBHOOK_DEDUP_WINDOW_SECONDS = 24L * 3600

    /** A run or log must outlive the writes and reads of the run's own end. */
    const val MIN_RUN_SECONDS = 3600L

    /** A scheduled time is fired up to a minute late; its claim must outlive that by far. */
    const val MIN_CRON_FIRING_SECONDS = 3600L
  }
}

/**
 * Everything the Engine takes from its environment, parsed and validated once at startup (12-factor
 * config). The Ktor configuration is only a container for `${ENV_VAR}` substitutions; see
 * `application.yaml` for the environment variable behind each key.
 */
data class EngineConfig(
    val database: DatabaseConfig,
    val tokens: List<ApiToken>,
    val initialAllowList: InitialAllowList,
    val upload: UploadConfig,
    val workspace: WorkspaceSettings,
    val runs: RunSettings,
    val retention: RetentionSettings,
    val telemetry: TelemetryConfig,
    val resources: ResourceSettings,
) {
  companion object {
    private const val DEFAULT_MAX_UPLOAD_BYTES = 50L * 1024 * 1024
    private const val DEFAULT_MAX_JAR_ENTRIES = 20_000L
    private const val DEFAULT_MAX_ENTRY_BYTES = 64L * 1024 * 1024
    private const val DEFAULT_MAX_EXPANDED_BYTES = 256L * 1024 * 1024
    private const val DEFAULT_SERVICE_NAME = "runline-engine"
    private const val DEFAULT_SWEEP_SECONDS = 300L
    /** How long a shutdown waits for requests in flight and for runs it asked to stop. */
    const val DEFAULT_SHUTDOWN_GRACE_SECONDS = 30L
    private const val DEFAULT_RESOURCE_WAIT_SECONDS = 3600L

    /** Parses [config]; reports all problems at once, without echoing any value. */
    fun from(config: ApplicationConfig): EngineConfig {
      val problems = mutableListOf<String>()

      fun text(key: String): String? =
          config.propertyOrNull(key)?.getString()?.trim()?.takeIf { it.isNotEmpty() }

      fun required(key: String): String = text(key) ?: "".also { problems += "$key is required" }

      fun optionalNumber(key: String, min: Long): Long? =
          text(key)?.let {
            it.toLongOrNull()?.takeIf { n -> n >= min }
                ?: null.also { problems += "$key must be a whole number of at least $min" }
          }

      fun number(key: String, min: Long): Long? {
        if (text(key) == null) problems += "$key is required"
        return optionalNumber(key, min)
      }

      fun optionalNumber(key: String, min: Long, problems: MutableList<String>): Long? =
          text(key)?.let {
            it.toLongOrNull()?.takeIf { n -> n >= min }
                ?: null.also { problems += "$key must be a whole number of at least $min" }
          }

      fun number(key: String, min: Long, problems: MutableList<String>): Long? =
          optionalNumber(key, min, problems)
              ?: null.also { if (text(key) == null) problems += "$key is required" }

      val database =
          DatabaseConfig(
              required("postgres.url"),
              required("postgres.user"),
              required("postgres.password"),
          )
      val tokens = parseTokens(text("auth.tokens"), problems)
      val allowList = initialAllowList(text("analysis.allowlist"), problems)
      val maxBytes =
          text("upload.maxBytes")?.let {
            it.toLongOrNull()?.takeIf { n -> n > 0 }
                ?: 0L.also { problems += "upload.maxBytes must be a positive number of bytes" }
          } ?: DEFAULT_MAX_UPLOAD_BYTES

      fun positive(key: String, default: Long): Long = optionalNumber(key, min = 1) ?: default

      val jarLimits =
          JarLimits(
              maxEntries =
                  positive("upload.maxEntries", DEFAULT_MAX_JAR_ENTRIES)
                      .coerceAtMost(Int.MAX_VALUE.toLong())
                      .toInt(),
              maxEntryBytes = positive("upload.maxEntryBytes", DEFAULT_MAX_ENTRY_BYTES),
              maxTotalBytes = positive("upload.maxExpandedBytes", DEFAULT_MAX_EXPANDED_BYTES),
          )

      val sharedRoot = required("workspace.sharedRoot")
      val runRoot = required("workspace.runRoot")
      val workspace =
          WorkspaceSettings(
              WorkspaceConfig(
                  Path.of(sharedRoot),
                  Path.of(runRoot),
                  number("workspace.maxBytes", min = 1) ?: 1L,
                  Duration.ofSeconds(number("workspace.failedRunRetentionSeconds", min = 0) ?: 0L),
              ),
              Duration.ofSeconds(
                  optionalNumber("workspace.sweepIntervalSeconds", min = 1) ?: DEFAULT_SWEEP_SECONDS
              ),
          )
      fun seconds(key: String, default: Long?, min: Long): Duration? =
          (optionalNumber(key, min) ?: default)?.let(Duration::ofSeconds)

      val runRetention =
          seconds(
              "retention.runSeconds",
              RetentionSettings.DEFAULT_RUN_SECONDS,
              RetentionSettings.MIN_RUN_SECONDS,
          )!!
      // The log is kept as long as its run unless set shorter.
      val logRetention =
          seconds("retention.logSeconds", runRetention.seconds, RetentionSettings.MIN_RUN_SECONDS)!!
      if (logRetention > runRetention) {
        problems += "retention.logSeconds must not be longer than retention.runSeconds"
      }
      val retention =
          RetentionSettings(
              runRetention,
              logRetention,
              seconds(
                  "retention.webhookDedupWindowSeconds",
                  RetentionSettings.DEFAULT_WEBHOOK_DEDUP_WINDOW_SECONDS,
                  RetentionSettings.MIN_WEBHOOK_DEDUP_WINDOW_SECONDS,
              )!!,
              seconds(
                  "retention.cronFiringSeconds",
                  RetentionSettings.DEFAULT_CRON_FIRING_SECONDS,
                  RetentionSettings.MIN_CRON_FIRING_SECONDS,
              )!!,
              seconds(
                  "retention.intervalSeconds",
                  RetentionSettings.DEFAULT_INTERVAL_SECONDS,
                  min = 1,
              )!!,
              (optionalNumber("retention.batchSize", min = 1)
                      ?: RetentionSettings.DEFAULT_BATCH_SIZE)
                  .coerceAtMost(Int.MAX_VALUE.toLong())
                  .toInt(),
          )
      val resourceRoot = required("resources.root")
      val runtimeDir = required("runs.runtimeDir")
      val runs =
          RunSettings(
              maxConcurrent = number("runs.maxConcurrent", min = 1)?.toInt() ?: 1,
              runtimeDir = Path.of(runtimeDir),
              timeout = optionalNumber("runs.timeoutSeconds", min = 1)?.let(Duration::ofSeconds),
              shutdownGrace =
                  Duration.ofSeconds(
                      optionalNumber("runs.shutdownGraceSeconds", min = 0)
                          ?: DEFAULT_SHUTDOWN_GRACE_SECONDS
                  ),
              resourceWaitTimeout =
                  Duration.ofSeconds(
                      optionalNumber("runs.resourceWaitTimeoutSeconds", min = 1)
                          ?: DEFAULT_RESOURCE_WAIT_SECONDS
                  ),
          )

      if (problems.isNotEmpty()) {
        throw ConfigurationException(invalid(problems))
      }
      return EngineConfig(
          database,
          tokens,
          allowList,
          UploadConfig(maxBytes, jarLimits),
          workspace,
          runs,
          retention,
          TelemetryConfig(text("telemetry.serviceName") ?: DEFAULT_SERVICE_NAME),
          ResourceSettings(Path.of(resourceRoot)),
      )
    }

    private fun parseTokens(raw: String?, problems: MutableList<String>): List<ApiToken> {
      if (raw == null) {
        problems += "auth.tokens is required (name:role:token, comma separated)"
        return emptyList()
      }
      val tokens =
          raw.split(',').mapIndexedNotNull { index, entry ->
            val parts = entry.trim().split(':', limit = 3)
            val role =
                parts.getOrNull(1)?.let { r ->
                  Role.entries.find { it.name.equals(r.trim(), true) }
                }
            val name = parts.getOrNull(0)?.trim().orEmpty()
            val secret = parts.getOrNull(2)?.trim().orEmpty()
            if (name.isEmpty() || role == null || secret.isEmpty()) {
              problems +=
                  "auth.tokens entry ${index + 1} must be name:role:token " +
                      "with role developer or admin"
              null
            } else {
              ApiToken(name, role, secret)
            }
          }
      if (tokens.map { it.secret }.toSet().size != tokens.size) {
        problems += "auth.tokens contains the same token more than once"
      }
      return tokens
    }

    /** The configured list when there is one; otherwise the project's default (WI-10). */
    private fun initialAllowList(raw: String?, problems: MutableList<String>): InitialAllowList =
        if (raw == null) InitialAllowList(DefaultAllowList.entries, fromConfiguration = false)
        else InitialAllowList(parseAllowList(raw, problems), fromConfiguration = true)

    /**
     * Reads the shared allow list text form (analyzer's `AllowListText`); every bad entry is a
     * problem.
     */
    private fun parseAllowList(raw: String, problems: MutableList<String>): List<AllowListEntry> {
      val parsed = AllowListText.parse(raw)
      parsed.problems.forEach {
        problems += "analysis.allowlist entry '${it.entry}' is invalid: ${it.reason}"
      }
      return parsed.entries
    }
  }
}
