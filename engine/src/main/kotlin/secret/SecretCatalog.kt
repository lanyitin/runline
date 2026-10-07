package dev.lawlan.runline.engine.secret

import dev.lawlan.runline.engine.auth.ApiIdentity
import dev.lawlan.runline.engine.resource.ResourceStore
import org.slf4j.LoggerFactory

/** One alias of the keystore, as an administrator sees it: never its value. */
data class ListedSecret(
    val alias: String,
    val kind: EntryKind,
    val status: AliasStatus,
    /** The names of the resources that use the alias. */
    val usedBy: List<String>,
)

/** An alias whose content differs after a reload, with the resources that use it. */
data class ChangedSecret(val alias: String, val usedBy: List<String>)

sealed interface SecretReloadOutcome {
  data class Reloaded(val aliases: Int, val changed: List<ChangedSecret>) : SecretReloadOutcome

  data class Failed(val failure: OpenFailure) : SecretReloadOutcome

  data object NotConfigured : SecretReloadOutcome
}

/**
 * What an administrator sees of the secrets and the reload (WI-41): the store's aliases joined with
 * the resources that name them, and the reload recorded with who asked and how it ended.
 */
class SecretCatalog(
    private val store: SecretStore,
    private val resources: ResourceStore,
    private val telemetry: SecretTelemetry,
) {
  private val log = LoggerFactory.getLogger(SecretCatalog::class.java)

  /** Null when the Engine has no keystore. */
  fun list(): List<ListedSecret>? {
    if (!store.configured) return null
    val users = usersByAlias()
    return store.entries().map {
      ListedSecret(it.alias, it.kind, it.status, users[it.alias].orEmpty())
    }
  }

  /**
   * Reads the keystore again. How it ended is logged with the name of the administrator and
   * counted; the log says aliases only as far as the result does (never a value).
   */
  fun reload(by: ApiIdentity): SecretReloadOutcome =
      when (val result = store.reload()) {
        ReloadResult.NotConfigured -> SecretReloadOutcome.NotConfigured
        is ReloadResult.Failed -> {
          log.warn("Secrets reload by {} failed: {}", by.name, result.failure.wire)
          telemetry.reloaded(result.failure.wire)
          SecretReloadOutcome.Failed(result.failure)
        }
        is ReloadResult.Reloaded -> {
          log.info(
              "Secrets reloaded by {}: {} aliases, {} changed",
              by.name,
              result.aliases,
              result.changed.size,
          )
          telemetry.reloaded("ok")
          val users = usersByAlias()
          SecretReloadOutcome.Reloaded(
              result.aliases,
              result.changed.map { ChangedSecret(it, users[it].orEmpty()) },
          )
        }
      }

  /** The names of the resources that use each alias, by alias as the store normalises it. */
  private fun usersByAlias(): Map<String, List<String>> =
      resources
          .list()
          .filter { it.secretAlias != null }
          .groupBy({ normalizeAlias(it.secretAlias!!) }, { it.name })
          .mapValues { (_, names) -> names.sorted() }
}
