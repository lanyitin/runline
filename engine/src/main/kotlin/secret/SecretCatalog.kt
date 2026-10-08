package dev.lawlan.runline.engine.secret

import dev.lawlan.runline.accessors.tls.TlsAliases
import dev.lawlan.runline.engine.auth.ApiIdentity
import dev.lawlan.runline.engine.resource.ResourceStore
import dev.lawlan.runline.engine.resource.SharedResource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import org.slf4j.LoggerFactory

/** One alias of the keystore, as an administrator sees it: never its value. */
data class ListedSecret(
    val alias: String,
    val kind: EntryKind,
    val status: AliasStatus,
    /** The names of the resources that use the alias. */
    val usedBy: List<String>,
    /** The certificates of a certificate or private key entry (WI-52); none for a secret. */
    val certificates: List<ListedCertificate> = emptyList(),
)

/** How near a certificate is to the end of its validity (WI-52). */
enum class Expiry(val wire: String) {
  VALID("valid"),

  /** Fewer days left than the Engine's warning threshold. */
  EXPIRING("expiring"),
  EXPIRED("expired"),
}

/** A certificate of an entry as it is listed: never anything of a key. */
data class ListedCertificate(val info: CertificateInfo, val daysLeft: Long, val expiry: Expiry)

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
    private val clock: Clock = Clock.systemUTC(),
    /** A certificate with fewer days left than this is listed as expiring (WI-52). */
    private val certificateWarningDays: Int = 30,
) {
  private val log = LoggerFactory.getLogger(SecretCatalog::class.java)

  init {
    telemetry.watchCertificates {
      val now = clock.instant()
      store
          .entries()
          .filter { it.certificates.isNotEmpty() }
          .associate { entry ->
            entry.alias to entry.certificates.minOf { listed(it, now).daysLeft }
          }
    }
  }

  /** Null when the Engine has no keystore. */
  fun list(): List<ListedSecret>? {
    if (!store.configured) return null
    val users = usersByAlias()
    val now = clock.instant()
    return store.entries().map {
      ListedSecret(
          it.alias,
          it.kind,
          it.status,
          users[it.alias].orEmpty(),
          it.certificates.map { info -> listed(info, now) },
      )
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

  private fun listed(info: CertificateInfo, now: Instant): ListedCertificate {
    val days = Math.floorDiv(Duration.between(now, info.notAfter).seconds, SECONDS_A_DAY)
    val expiry =
        when {
          info.notAfter.isBefore(now) -> Expiry.EXPIRED
          days < certificateWarningDays -> Expiry.EXPIRING
          else -> Expiry.VALID
        }
    return ListedCertificate(info, days, expiry)
  }

  /**
   * The names of the resources that use each alias, by alias as the store normalises it: as their
   * secret, and as a trusted or client certificate of their settings (WI-52).
   */
  private fun usersByAlias(): Map<String, List<String>> =
      resources
          .list()
          .flatMap { resource -> aliasesOf(resource).map { it to resource.name } }
          .groupBy({ it.first }, { it.second })
          .mapValues { (_, names) -> names.distinct().sorted() }

  private fun aliasesOf(resource: SharedResource): List<String> {
    val tls =
        (TlsAliases.parse(resource.settings) as? TlsAliases.Parsed.Valid)?.aliases
            ?: TlsAliases.NONE
    return (listOfNotNull(resource.secretAlias, tls.clientCertAlias) + tls.trustAliases).map(
        ::normalizeAlias
    )
  }

  private companion object {
    const val SECONDS_A_DAY = 24L * 3600
  }
}
