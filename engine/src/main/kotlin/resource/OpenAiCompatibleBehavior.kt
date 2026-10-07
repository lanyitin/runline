package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.accessors.ResourceBinding
import dev.lawlan.runline.accessors.openai.OpenAiBinding
import dev.lawlan.runline.accessors.openai.OpenAiCredential
import dev.lawlan.runline.accessors.openai.OpenAiObserver
import dev.lawlan.runline.accessors.openai.OpenAiProbe
import dev.lawlan.runline.accessors.openai.OpenAiSettings
import dev.lawlan.runline.accessors.openai.OpenAiSettingsProblem
import dev.lawlan.runline.accessors.openai.SettingsResult
import dev.lawlan.runline.core.ResourceFailure
import dev.lawlan.runline.engine.secret.SecretLookup
import dev.lawlan.runline.engine.secret.SecretStore
import java.time.Duration
import kotlinx.serialization.json.JsonObject

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
  override fun problemWith(settings: JsonObject?, secretAlias: String?): InvalidResource? {
    if (settings == null) return InvalidResource.INVALID_SETTINGS
    when (val parsed = OpenAiSettings.parse(settings)) {
      is SettingsResult.Invalid -> return parsed.problem.toInvalid()
      is SettingsResult.Valid -> Unit
    }
    if (secretAlias != null && !ALIAS.matches(secretAlias)) {
      return InvalidResource.INVALID_SECRET_ALIAS
    }
    return null
  }

  override fun normalized(settings: JsonObject): JsonObject =
      (OpenAiSettings.parse(settings) as SettingsResult.Valid).normalized

  override fun concurrencyLimit(resource: SharedResource): Int? =
      settingsOf(resource)?.let { resource.capacity * it.requestsPerRun }

  override fun bind(resource: SharedResource): ResourceBinding {
    val settings = settingsOf(resource) ?: throw ResourceUnavailable(resource.name)
    return OpenAiBinding(resource.name, settings, credentialOf(resource), observer)
  }

  override fun check(resource: SharedResource): CheckFailure? {
    val settings = settingsOf(resource) ?: return CheckFailure.ERROR
    val credential = credentialOf(resource)
    if (credential is OpenAiCredential.Unavailable) {
      return if (secrets.lookup(resource.secretAlias!!) is SecretLookup.Invalid) {
        CheckFailure.ALIAS_INVALID
      } else {
        CheckFailure.ALIAS_MISSING
      }
    }
    val failure = OpenAiProbe.check(settings, credential, checkTimeout.toMillis()) ?: return null
    return when (failure) {
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

  private fun settingsOf(resource: SharedResource): OpenAiSettings? =
      (OpenAiSettings.parse(resource.settings) as? SettingsResult.Valid)?.settings

  /** The key as the keystore has it now; a key it cannot give leaves the resource unusable. */
  private fun credentialOf(resource: SharedResource): OpenAiCredential {
    val alias = resource.secretAlias ?: return OpenAiCredential.None
    return when (val found = secrets.lookup(alias)) {
      is SecretLookup.Found -> OpenAiCredential.Key(found.value.reveal())
      SecretLookup.Missing,
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
      }

  private companion object {
    /** Written like the name of a resource (ADR-019, WI-46). */
    val ALIAS = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")
  }
}
