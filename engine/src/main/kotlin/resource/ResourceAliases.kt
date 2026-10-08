package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.accessors.tls.ResourceTls
import dev.lawlan.runline.accessors.tls.TlsAliases
import dev.lawlan.runline.engine.secret.EntryLookup
import dev.lawlan.runline.engine.secret.SecretLookup
import dev.lawlan.runline.engine.secret.SecretStore
import java.security.cert.X509Certificate

/**
 * What the certificate aliases of a resource come to against the keystore as it is now (WI-52):
 * whether each names an entry of its kind, the certificates to use, and those to show. The secret
 * alias shares the namespace and is held to its kind here too.
 */
internal class ResourceAliases(private val secrets: SecretStore) {
  /** Whether any of the aliases names an entry of another kind than its member wants. */
  fun wrongType(tls: TlsAliases, secretAlias: String?): Boolean =
      (secretAlias != null && secrets.lookup(secretAlias) == SecretLookup.WrongType) ||
          tls.trustAliases.any { secrets.trustedCertificate(it) == EntryLookup.WrongType } ||
          tls.clientCertAlias?.let { secrets.clientCertificate(it) == EntryLookup.WrongType } ==
              true

  /** The status of each trusted certificate alias, in order. */
  fun trustStates(tls: TlsAliases): List<Pair<String, AliasState>> =
      tls.trustAliases.map { it to stateOf(secrets.trustedCertificate(it)) }

  fun clientState(tls: TlsAliases): AliasState =
      tls.clientCertAlias?.let { stateOf(secrets.clientCertificate(it)) } ?: AliasState.NOT_SET

  private fun stateOf(lookup: EntryLookup<*>): AliasState =
      when (lookup) {
        is EntryLookup.Found -> AliasState.FOUND
        EntryLookup.Missing -> AliasState.MISSING
        EntryLookup.WrongType -> AliasState.WRONG_TYPE
        EntryLookup.Invalid -> AliasState.INVALID_KEY
      }

  /** What the aliases come to: the TLS to use, or the state of the first alias that cannot be. */
  sealed interface Resolved {
    /** [tls] is null when the resource names no certificate. */
    data class Ready(val tls: ResourceTls?) : Resolved

    data class Unusable(val state: AliasState) : Resolved
  }

  fun resolve(tls: TlsAliases): Resolved {
    if (tls.isEmpty) return Resolved.Ready(null)
    val trusted =
        tls.trustAliases.map {
          when (val found = secrets.trustedCertificate(it)) {
            is EntryLookup.Found -> found.value
            else -> return Resolved.Unusable(stateOf(found))
          }
        }
    val client =
        tls.clientCertAlias?.let {
          when (val found = secrets.clientCertificate(it)) {
            is EntryLookup.Found -> found.value
            else -> return Resolved.Unusable(stateOf(found))
          }
        }
    return Resolved.Ready(ResourceTls(trusted, client))
  }

  /** Each certificate the aliases name, by alias: the trusted ones, then the client's chain. */
  fun certificates(tls: TlsAliases): List<Pair<String, X509Certificate>> =
      tls.trustAliases.mapNotNull { alias ->
        (secrets.trustedCertificate(alias) as? EntryLookup.Found)?.let { alias to it.value }
      } +
          tls.clientCertAlias
              ?.let { alias ->
                (secrets.clientCertificate(alias) as? EntryLookup.Found)?.value?.chain?.map {
                  alias to it
                }
              }
              .orEmpty()

  /** The check's failure for the secret alias, or null when there is none or it can be used. */
  fun secretFailure(alias: String?): CheckFailure? =
      when (alias?.let(secrets::lookup)) {
        null,
        is SecretLookup.Found -> null
        SecretLookup.Missing -> CheckFailure.ALIAS_MISSING
        SecretLookup.Invalid -> CheckFailure.ALIAS_INVALID
        SecretLookup.WrongType -> CheckFailure.ALIAS_WRONG_TYPE
      }

  /** The check's failure for an alias in [state]. */
  fun failureOf(state: AliasState): CheckFailure =
      when (state) {
        AliasState.WRONG_TYPE -> CheckFailure.ALIAS_WRONG_TYPE
        AliasState.INVALID_KEY,
        AliasState.INVALID_SECRET -> CheckFailure.ALIAS_INVALID
        else -> CheckFailure.ALIAS_MISSING
      }
}
