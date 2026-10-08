package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.accessors.ResourceBinding
import dev.lawlan.runline.accessors.openai.OpenAiBinding
import dev.lawlan.runline.accessors.openai.OpenAiCredential
import dev.lawlan.runline.accessors.openai.OpenAiEndpoints
import dev.lawlan.runline.accessors.openai.OpenAiObserver
import dev.lawlan.runline.accessors.openai.OpenAiProbe
import dev.lawlan.runline.accessors.openai.OpenAiSettings
import dev.lawlan.runline.accessors.openai.OpenAiSettingsProblem
import dev.lawlan.runline.accessors.openai.SettingsResult
import dev.lawlan.runline.accessors.tls.TlsAliases
import dev.lawlan.runline.accessors.tls.TlsFailure
import dev.lawlan.runline.core.ResourceFailure
import dev.lawlan.runline.engine.secret.SecretLookup
import dev.lawlan.runline.engine.secret.SecretStore
import java.security.cert.X509Certificate
import java.time.Duration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * An OpenAI compatible service (ADR-019): the settings are the administrator's, the key is named by
 * an alias of the keystore and read when a run gets the resource, and the run gets an accessor that
 * can only call the entries of the endpoint catalog the administrator enabled. Which request is
 * sent, how long it may take and how many may be in flight is the accessors' business
 * (`OpenAiBinding`); this class is what the Engine adds: the keystore and the check.
 */
internal class OpenAiCompatibleBehavior(
    private val secrets: SecretStore,
    private val checkTimeout: Duration,
    private val observer: OpenAiObserver,
) : ResourceBehavior {
  private val aliases = ResourceAliases(secrets)

  override fun problemWith(settings: JsonObject?, secretAlias: String?): InvalidResource? {
    if (settings == null) return InvalidResource.INVALID_SETTINGS
    val parsed =
        when (val result = OpenAiSettings.parse(settings)) {
          is SettingsResult.Invalid -> return result.problem.toInvalid()
          is SettingsResult.Valid -> result.settings
        }
    if (secretAlias != null && !ALIAS.matches(secretAlias)) {
      return InvalidResource.INVALID_SECRET_ALIAS
    }
    if (aliases.wrongType(parsed.tls, secretAlias)) return InvalidResource.ALIAS_WRONG_TYPE
    return null
  }

  override fun normalized(settings: JsonObject): JsonObject =
      (OpenAiSettings.parse(settings) as SettingsResult.Valid).normalized

  /**
   * The entries that can be enabled, each with what is fixed about it, and the request parameters a
   * resource may default, lock or cap, with the kind of value each takes.
   */
  override fun description(): JsonObject = buildJsonObject {
    putJsonArray("endpoints") {
      for (entry in OpenAiEndpoints.enableable) {
        addJsonObject {
          put("id", entry.id)
          put("group", entry.group)
          put("method", entry.method)
          put("path", entry.path)
          put("request", entry.body.name.lowercase())
          put("response", entry.response.name.lowercase())
          put("streams", entry.streams || entry.streamsBytes)
          put("defaultEnabled", entry.defaultEnabled)
          put("stateful", entry.stateful)
        }
      }
    }
    putJsonArray("requestParameters") {
      for (parameter in OpenAiSettings.REQUEST_PARAMETERS) {
        addJsonObject {
          put("name", parameter.name)
          put("kind", parameter.kind.wire)
          put("ceiling", parameter.ceiling)
        }
      }
    }
  }

  override fun concurrencyLimit(resource: SharedResource): Int? =
      settingsOf(resource)?.let { resource.capacity * it.requestsPerRun }

  override fun tlsAliasesOf(resource: SharedResource): TlsAliases =
      settingsOf(resource)?.tls ?: TlsAliases.NONE

  override fun hostOf(resource: SharedResource): String? = settingsOf(resource)?.baseUrl?.host

  override fun certificatesOf(resource: SharedResource): List<Pair<String, X509Certificate>> =
      aliases.certificates(tlsAliasesOf(resource))

  /**
   * The key and the certificates as the keystore has them now. Certificates it cannot give leave
   * the resource unusable, as a key does: the JVM's default trust is never used instead.
   */
  override fun bind(resource: SharedResource): ResourceBinding {
    val settings = settingsOf(resource) ?: throw ResourceUnavailable(resource.name)
    return when (val tls = aliases.resolve(settings.tls)) {
      is ResourceAliases.Resolved.Unusable ->
          OpenAiBinding(resource.name, settings, OpenAiCredential.Unavailable, observer)
      is ResourceAliases.Resolved.Ready ->
          OpenAiBinding(resource.name, settings, credentialOf(resource), observer, tls.tls)
    }
  }

  override fun check(resource: SharedResource): CheckFailure? {
    val settings = settingsOf(resource) ?: return CheckFailure.ERROR
    aliases.secretFailure(resource.secretAlias)?.let {
      return it
    }
    val tls =
        when (val resolved = aliases.resolve(settings.tls)) {
          is ResourceAliases.Resolved.Unusable -> return aliases.failureOf(resolved.state)
          is ResourceAliases.Resolved.Ready -> resolved.tls
        }
    val failure =
        OpenAiProbe.check(settings, credentialOf(resource), checkTimeout.toMillis(), tls)
            ?: return null
    failure.tls?.let {
      return it.toCheck()
    }
    return when (failure.failure) {
      ResourceFailure.CONNECTION_FAILED -> CheckFailure.CONNECTION_FAILED
      ResourceFailure.CONNECT_TIMEOUT,
      ResourceFailure.FIRST_BYTE_TIMEOUT,
      ResourceFailure.IDLE_TIMEOUT,
      ResourceFailure.TOTAL_TIMEOUT -> CheckFailure.TIMEOUT
      ResourceFailure.DENIED -> CheckFailure.REJECTED
      ResourceFailure.SERVER_ERROR -> CheckFailure.SERVER_ERROR
      ResourceFailure.REDIRECT_BLOCKED -> CheckFailure.REDIRECT_BLOCKED
      ResourceFailure.REQUEST_REJECTED,
      ResourceFailure.RESPONSE_TOO_LARGE -> CheckFailure.UNEXPECTED_RESPONSE
      else -> CheckFailure.ERROR
    }
  }

  /** The check's category for a failure of TLS: the same word (WI-52). */
  private fun TlsFailure.toCheck(): CheckFailure = CheckFailure.fromWire(wire)

  private fun settingsOf(resource: SharedResource): OpenAiSettings? =
      (OpenAiSettings.parse(resource.settings) as? SettingsResult.Valid)?.settings

  /** The key as the keystore has it now; a key it cannot give leaves the resource unusable. */
  private fun credentialOf(resource: SharedResource): OpenAiCredential {
    val alias = resource.secretAlias ?: return OpenAiCredential.None
    return when (val found = secrets.lookup(alias)) {
      is SecretLookup.Found -> OpenAiCredential.Key(found.value.reveal())
      SecretLookup.Missing,
      SecretLookup.WrongType,
      SecretLookup.Invalid -> OpenAiCredential.Unavailable
    }
  }

  private fun OpenAiSettingsProblem.toInvalid() =
      when (this) {
        OpenAiSettingsProblem.INVALID_SETTINGS -> InvalidResource.INVALID_SETTINGS
        OpenAiSettingsProblem.INVALID_BASE_URL -> InvalidResource.INVALID_BASE_URL
        OpenAiSettingsProblem.INVALID_HEADER -> InvalidResource.INVALID_HEADER
        OpenAiSettingsProblem.INVALID_ENDPOINT -> InvalidResource.INVALID_ENDPOINT
        OpenAiSettingsProblem.INVALID_REQUEST_DEFAULTS -> InvalidResource.INVALID_REQUEST_DEFAULTS
        OpenAiSettingsProblem.INVALID_TIMEOUT -> InvalidResource.INVALID_TIMEOUT
        OpenAiSettingsProblem.INVALID_LIMIT -> InvalidResource.INVALID_LIMIT
        OpenAiSettingsProblem.INVALID_ALIAS -> InvalidResource.INVALID_SECRET_ALIAS
      }

  private companion object {
    /** Written like the name of a resource (ADR-019, WI-46). */
    val ALIAS = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")
  }
}
