package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.accessors.ResourceBinding
import dev.lawlan.runline.engine.secret.SecretLookup
import dev.lawlan.runline.engine.secret.SecretStore
import dev.lawlan.runline.engine.traceexport.TraceExportBinding
import dev.lawlan.runline.engine.traceexport.TraceExportSettings
import dev.lawlan.runline.engine.traceexport.TraceExportSettingsProblem
import dev.lawlan.runline.engine.traceexport.TraceExporters
import dev.lawlan.runline.engine.traceexport.UnavailableSink
import java.time.Duration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The capacity of every `trace-export` resource (ADR-46 decision 10): near enough to unlimited that
 * a run never waits to get the resource, and fixed, so that no administrator edits it.
 */
const val TRACE_EXPORT_CAPACITY = 1_000_000

/**
 * Spans exported to an OTLP endpoint (ADR-46): the settings are the administrator's, the credential
 * is named by an alias of the keystore and read whenever a batch goes out, and a run gets an
 * accessor that takes finished spans and hands them to the resource's exporter.
 */
internal class TraceExportBehavior(
    private val secrets: SecretStore,
    private val checkTimeout: Duration,
    private val exporters: TraceExporters,
) : ResourceBehavior {
  private val aliases = ResourceAliases(secrets)

  override fun problemWith(settings: JsonObject?, secretAlias: String?): InvalidResource? {
    val parsed =
        when (val result = TraceExportSettings.parse(settings)) {
          is TraceExportSettings.Result.Invalid -> return result.problem.toInvalid()
          is TraceExportSettings.Result.Valid -> result.settings
        }
    // The credential is the point of the resource: an alias is required.
    if (secretAlias == null || !ALIAS.matches(secretAlias)) {
      return InvalidResource.INVALID_SECRET_ALIAS
    }
    if (aliases.wrongType(parsed.tls, secretAlias)) return InvalidResource.ALIAS_WRONG_TYPE
    return null
  }

  override val fixedCapacity: Int = TRACE_EXPORT_CAPACITY

  /** What the Console needs that the Engine fixes: the capacity it does not let anyone edit. */
  override fun description(): JsonObject = buildJsonObject { put("fixedCapacity", fixedCapacity) }

  override fun normalized(settings: JsonObject): JsonObject =
      (TraceExportSettings.parse(settings) as TraceExportSettings.Result.Valid)
          .settings
          .normalized()

  /**
   * Never refuses the run: tracing must not decide whether a pipeline can run. Certificates the
   * keystore cannot give leave the resource unable to export, and what is submitted is counted as
   * dropped.
   */
  override fun bind(resource: SharedResource): ResourceBinding {
    val settings =
        (TraceExportSettings.parse(resource.settings) as? TraceExportSettings.Result.Valid)
            ?.settings
    val lease = settings?.let {
      (aliases.resolve(it.tls) as? ResourceAliases.Resolved.Ready)?.let { tls ->
        exporters.acquire(resource.name, it, resource.secretAlias, tls.tls)
      }
    }
    return TraceExportBinding(
        resource.name,
        lease?.sink ?: UnavailableSink,
        exporters.tuning,
        exporters.telemetry,
    ) {
      lease?.close()
    }
  }

  override fun check(resource: SharedResource): CheckFailure? {
    val settings = settingsOf(resource) ?: return CheckFailure.ERROR
    val alias = resource.secretAlias ?: return CheckFailure.ALIAS_MISSING
    aliases.secretFailure(alias)?.let {
      return it
    }
    val tls =
        when (val resolved = aliases.resolve(settings.tls)) {
          is ResourceAliases.Resolved.Unusable -> return aliases.failureOf(resolved.state)
          is ResourceAliases.Resolved.Ready -> resolved.tls
        }
    val credential =
        (secrets.lookup(alias) as? SecretLookup.Found)?.value?.reveal()
            ?: return CheckFailure.ALIAS_MISSING
    return TraceExportProbe.check(settings.endpoint, credential, checkTimeout, tls)
  }

  private fun settingsOf(resource: SharedResource): TraceExportSettings? =
      (TraceExportSettings.parse(resource.settings) as? TraceExportSettings.Result.Valid)?.settings

  private fun TraceExportSettingsProblem.toInvalid() =
      when (this) {
        TraceExportSettingsProblem.INVALID_SETTINGS -> InvalidResource.INVALID_SETTINGS
        TraceExportSettingsProblem.INVALID_ENDPOINT -> InvalidResource.INVALID_BASE_URL
        TraceExportSettingsProblem.INVALID_ALIAS -> InvalidResource.INVALID_SECRET_ALIAS
      }

  private companion object {
    /** Written like the name of a resource (ADR-019, WI-46). */
    val ALIAS = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")
  }
}
