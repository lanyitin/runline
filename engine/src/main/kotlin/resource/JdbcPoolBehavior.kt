package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.accessors.ResourceBinding
import dev.lawlan.runline.accessors.jdbc.JdbcCredential
import dev.lawlan.runline.accessors.jdbc.JdbcObserver
import dev.lawlan.runline.accessors.jdbc.JdbcPools
import dev.lawlan.runline.accessors.jdbc.JdbcProbe
import dev.lawlan.runline.accessors.jdbc.JdbcProfiles
import dev.lawlan.runline.accessors.jdbc.JdbcSettings
import dev.lawlan.runline.accessors.jdbc.JdbcSettingsProblem
import dev.lawlan.runline.accessors.jdbc.JdbcSettingsResult
import dev.lawlan.runline.core.ResourceFailure
import dev.lawlan.runline.engine.secret.SecretLookup
import dev.lawlan.runline.engine.secret.SecretStore
import java.time.Duration
import kotlinx.serialization.json.JsonObject

/**
 * A database behind a connection pool (ADR-019, WI-48): the settings are the administrator's, the
 * password is named by an alias of the keystore and read when a run gets the resource, and the run
 * gets an accessor that can only send SQL to that database as that account. Which database kinds
 * exist is the profiles' business (`JdbcProfiles`); what the Engine adds here is the keystore, the
 * check and the way settings are told to the API.
 */
internal class JdbcPoolBehavior(
    private val profiles: JdbcProfiles,
    private val pools: JdbcPools,
    private val secrets: SecretStore,
    private val checkTimeout: Duration,
    private val observer: JdbcObserver,
) : ResourceBehavior {
  override fun problemWith(settings: JsonObject?, secretAlias: String?): InvalidResource? {
    if (settings == null) return InvalidResource.INVALID_SETTINGS
    when (val parsed = JdbcSettings.parse(settings, profiles)) {
      is JdbcSettingsResult.Invalid -> return parsed.problem.toInvalid()
      is JdbcSettingsResult.Valid -> Unit
    }
    if (secretAlias != null && !ALIAS.matches(secretAlias)) {
      return InvalidResource.INVALID_SECRET_ALIAS
    }
    return null
  }

  override fun normalized(settings: JsonObject): JsonObject =
      (JdbcSettings.parse(settings, profiles) as JdbcSettingsResult.Valid).normalized

  /** The connections the pool has room for: what every run that may hold the resource can use. */
  override fun concurrencyLimit(resource: SharedResource): Int? =
      settingsOf(resource)?.let { resource.capacity * it.connectionsPerRun }

  override fun hostOf(resource: SharedResource): String? = settingsOf(resource)?.host

  override fun bind(resource: SharedResource): ResourceBinding {
    val settings = settingsOf(resource) ?: throw ResourceUnavailable(resource.name)
    return pools.bind(resource.name, settings, credentialOf(resource), resource.capacity, observer)
  }

  override fun check(resource: SharedResource): CheckFailure? {
    val settings = settingsOf(resource) ?: return CheckFailure.ERROR
    val credential = credentialOf(resource)
    if (credential is JdbcCredential.Unavailable) {
      return if (secrets.lookup(resource.secretAlias!!) is SecretLookup.Invalid) {
        CheckFailure.ALIAS_INVALID
      } else {
        CheckFailure.ALIAS_MISSING
      }
    }
    val profile = profiles.find(settings.kind) ?: return CheckFailure.ERROR
    val failure =
        JdbcProbe.check(profile, settings, credential, checkTimeout.toMillis()) ?: return null
    return when (failure.failure) {
      ResourceFailure.CONNECTION_FAILED -> CheckFailure.CONNECTION_FAILED
      ResourceFailure.CONNECT_TIMEOUT,
      ResourceFailure.TOTAL_TIMEOUT -> CheckFailure.TIMEOUT
      ResourceFailure.DENIED -> CheckFailure.REJECTED
      ResourceFailure.SQL_ERROR -> CheckFailure.UNEXPECTED_RESPONSE
      else -> CheckFailure.ERROR
    }
  }

  private fun settingsOf(resource: SharedResource): JdbcSettings? =
      (JdbcSettings.parse(resource.settings, profiles) as? JdbcSettingsResult.Valid)?.settings

  /** The password as the keystore has it now; one it cannot give leaves the resource unusable. */
  private fun credentialOf(resource: SharedResource): JdbcCredential {
    val alias = resource.secretAlias ?: return JdbcCredential.None
    return when (val found = secrets.lookup(alias)) {
      is SecretLookup.Found -> JdbcCredential.Password(found.value.reveal())
      SecretLookup.Missing,
      SecretLookup.Invalid -> JdbcCredential.Unavailable
    }
  }

  private fun JdbcSettingsProblem.toInvalid() =
      when (this) {
        JdbcSettingsProblem.INVALID_SETTINGS -> InvalidResource.INVALID_SETTINGS
        JdbcSettingsProblem.UNSUPPORTED_DATABASE -> InvalidResource.UNSUPPORTED_DATABASE
        JdbcSettingsProblem.PROPERTY_NOT_ALLOWED -> InvalidResource.PROPERTY_NOT_ALLOWED
        JdbcSettingsProblem.INVALID_TIMEOUT -> InvalidResource.INVALID_TIMEOUT
        JdbcSettingsProblem.INVALID_LIMIT -> InvalidResource.INVALID_LIMIT
        JdbcSettingsProblem.INVALID_ALIAS -> InvalidResource.INVALID_SECRET_ALIAS
      }

  private companion object {
    /** Written like the name of a resource (ADR-019, WI-46). */
    val ALIAS = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")
  }
}
