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
import dev.lawlan.runline.accessors.jdbc.PropertyRule
import dev.lawlan.runline.accessors.tls.TlsAliases
import dev.lawlan.runline.accessors.tls.TlsFailure
import dev.lawlan.runline.core.ResourceFailure
import dev.lawlan.runline.engine.secret.SecretLookup
import dev.lawlan.runline.engine.secret.SecretStore
import java.security.cert.X509Certificate
import java.time.Duration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

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
  private val aliases = ResourceAliases(secrets)

  override fun problemWith(settings: JsonObject?, secretAlias: String?): InvalidResource? {
    if (settings == null) return InvalidResource.INVALID_SETTINGS
    val parsed =
        when (val result = JdbcSettings.parse(settings, profiles)) {
          is JdbcSettingsResult.Invalid -> return result.problem.toInvalid()
          is JdbcSettingsResult.Valid -> result.settings
        }
    if (secretAlias != null && !ALIAS.matches(secretAlias)) {
      return InvalidResource.INVALID_SECRET_ALIAS
    }
    if (aliases.wrongType(parsed.tls, secretAlias)) return InvalidResource.ALIAS_WRONG_TYPE
    return null
  }

  override fun normalized(settings: JsonObject): JsonObject =
      (JdbcSettings.parse(settings, profiles) as JdbcSettingsResult.Valid).normalized

  /** Each database kind the Engine carries, with the extra properties it allows and their rules. */
  override fun description(): JsonObject = buildJsonObject {
    putJsonArray("databases") {
      for (profile in profiles.all) {
        addJsonObject {
          put("kind", profile.kind)
          putJsonArray("properties") {
            for ((name, rule) in profile.allowedProperties) {
              addJsonObject {
                put("name", name)
                when (rule) {
                  is PropertyRule.Text -> {
                    put("rule", "text")
                    put("maxLength", rule.maxLength)
                  }
                  is PropertyRule.OneOf -> {
                    put("rule", "oneOf")
                    putJsonArray("values") { rule.values.forEach { add(it) } }
                  }
                  is PropertyRule.Pattern -> {
                    put("rule", "pattern")
                    put("pattern", rule.pattern)
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  /** The connections the pool has room for: what every run that may hold the resource can use. */
  override fun concurrencyLimit(resource: SharedResource): Int? =
      settingsOf(resource)?.let { resource.capacity * it.connectionsPerRun }

  override fun tlsAliasesOf(resource: SharedResource): TlsAliases =
      settingsOf(resource)?.tls ?: TlsAliases.NONE

  override fun hostOf(resource: SharedResource): String? = settingsOf(resource)?.host

  override fun certificatesOf(resource: SharedResource): List<Pair<String, X509Certificate>> =
      aliases.certificates(tlsAliasesOf(resource))

  /**
   * The password and the certificates as the keystore has them now. Certificates it cannot give
   * leave the resource unusable, as a password does: the connection is never made without them.
   */
  override fun bind(resource: SharedResource): ResourceBinding {
    val settings = settingsOf(resource) ?: throw ResourceUnavailable(resource.name)
    return when (val tls = aliases.resolve(settings.tls)) {
      is ResourceAliases.Resolved.Unusable ->
          pools.bind(
              resource.name,
              settings,
              JdbcCredential.Unavailable,
              resource.capacity,
              observer,
          )
      is ResourceAliases.Resolved.Ready ->
          pools.bind(
              resource.name,
              settings,
              credentialOf(resource),
              resource.capacity,
              observer,
              tls.tls,
          )
    }
  }

  /** Every generation of the pool is closed, with its connections (WI-65). */
  override fun removed(name: String) = pools.remove(name)

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
    val profile = profiles.find(settings.kind) ?: return CheckFailure.ERROR
    val failure =
        JdbcProbe.check(profile, settings, credentialOf(resource), checkTimeout.toMillis(), tls)
            ?: return null
    failure.tls?.let {
      return it.toCheck()
    }
    return when (failure.failure) {
      ResourceFailure.CONNECTION_FAILED -> CheckFailure.CONNECTION_FAILED
      ResourceFailure.CONNECT_TIMEOUT,
      ResourceFailure.TOTAL_TIMEOUT -> CheckFailure.TIMEOUT
      ResourceFailure.DENIED -> CheckFailure.REJECTED
      ResourceFailure.SQL_ERROR -> CheckFailure.UNEXPECTED_RESPONSE
      else -> CheckFailure.ERROR
    }
  }

  /** The check's category for a failure of TLS: the same word (WI-52). */
  private fun TlsFailure.toCheck(): CheckFailure = CheckFailure.fromWire(wire)

  private fun settingsOf(resource: SharedResource): JdbcSettings? =
      (JdbcSettings.parse(resource.settings, profiles) as? JdbcSettingsResult.Valid)?.settings

  /** The password as the keystore has it now; one it cannot give leaves the resource unusable. */
  private fun credentialOf(resource: SharedResource): JdbcCredential {
    val alias = resource.secretAlias ?: return JdbcCredential.None
    return when (val found = secrets.lookup(alias)) {
      is SecretLookup.Found -> JdbcCredential.Password(found.value.reveal())
      SecretLookup.Missing,
      SecretLookup.WrongType,
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
