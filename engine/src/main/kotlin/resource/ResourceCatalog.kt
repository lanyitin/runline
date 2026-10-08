package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.accessors.jdbc.JdbcPools
import dev.lawlan.runline.accessors.tls.TlsAliases
import dev.lawlan.runline.engine.auth.ApiIdentity
import dev.lawlan.runline.engine.secret.SecretLookup
import dev.lawlan.runline.engine.secret.SecretStore
import java.util.UUID

/** What a resource's secret alias comes to against the keystore; never what the secret is. */
enum class AliasState(val wire: String) {
  /** The resource names no alias. */
  NOT_SET("not_set"),
  FOUND("found"),

  /** The alias is not in the keystore, or the Engine has no keystore. */
  MISSING("missing"),

  /** The alias is there but its secret cannot be used (ADR-019 decision 11). */
  INVALID_SECRET("invalid_secret"),

  /** The alias names an entry of another kind than the member wants (WI-52). */
  WRONG_TYPE("wrong_type"),

  /** The alias names a private key the keystore's password does not open (WI-52). */
  INVALID_KEY("invalid_key"),
}

/**
 * A resource's definition together with who holds it and who waits for it right now, and which
 * pipeline definitions declare it.
 */
data class ResourceView(
    val resource: SharedResource,
    val activity: ResourceActivity,
    val declarations: ResourceDeclarations,
    val aliasState: AliasState = AliasState.NOT_SET,
    /** How many requests the entity can have at once, for the types that can say. */
    val concurrencyLimit: Int? = null,
    /** What the type says about its use right now; null for a type that has nothing to say. */
    val usage: ResourceUsage? = null,
    /** Each trusted certificate alias with its status (WI-52), in order. */
    val trustStates: List<Pair<String, AliasState>> = emptyList(),
    /** The client certificate alias's status (WI-52). */
    val clientCertState: AliasState = AliasState.NOT_SET,
)

sealed interface ForceReleaseOutcome {
  data class Released(val holder: Holder) : ForceReleaseOutcome

  data object ResourceNotFound : ForceReleaseOutcome

  /** The resource exists but the run does not hold it. */
  data object NotHeld : ForceReleaseOutcome
}

/**
 * What an administrator sees of shared resources: the definitions joined with the runtime state
 * kept by the [ResourceCoordinator] and with the pipeline definitions that declare them, and the
 * forced release of a holder.
 */
class ResourceCatalog(
    private val store: ResourceStore,
    private val coordinator: ResourceCoordinator,
    private val declarations: ResourceDeclarationStore,
    private val behaviors: ResourceBehaviors = ResourceBehaviors.countersOnly(),
    private val secrets: SecretStore = dev.lawlan.runline.engine.secret.NoSecretStore,
    private val usage: OpenAiUsage = OpenAiUsage(),
    private val jdbcPools: JdbcPools? = null,
) {
  private val certificates = ResourceAliases(secrets)

  fun list(): List<ResourceView> = views(store.list())

  fun find(name: String): ResourceView? = store.find(name)?.let { views(listOf(it)).single() }

  fun forceRelease(name: String, runId: UUID, by: ApiIdentity): ForceReleaseOutcome {
    store.find(name) ?: return ForceReleaseOutcome.ResourceNotFound
    return when (val result = coordinator.forceRelease(name, runId, by)) {
      is ForceReleaseResult.Released -> ForceReleaseOutcome.Released(result.holder)
      ForceReleaseResult.NotHeld -> ForceReleaseOutcome.NotHeld
    }
  }

  private fun views(resources: List<SharedResource>): List<ResourceView> {
    val declaredBy = declarations.declaredBy(resources.map { it.name })
    return resources.map {
      val tls = behaviors.of(it.type)?.tlsAliasesOf(it) ?: TlsAliases.NONE
      ResourceView(
          it,
          coordinator.activity(it.name),
          declaredBy.getValue(it.name),
          aliasStateOf(it),
          behaviors.of(it.type)?.concurrencyLimit(it),
          usageOf(it),
          certificates.trustStates(tls),
          certificates.clientState(tls),
      )
    }
  }

  private fun usageOf(resource: SharedResource): ResourceUsage? =
      when (resource.type) {
        ResourceType.OPENAI_COMPATIBLE ->
            ResourceUsage(inFlightRequests = usage.inFlight(resource.name))
        ResourceType.JDBC_POOL ->
            ResourceUsage(activeConnections = jdbcPools?.activeConnections(resource.name) ?: 0)
        else -> null
      }

  private fun aliasStateOf(resource: SharedResource): AliasState {
    val alias = resource.secretAlias ?: return AliasState.NOT_SET
    return when (secrets.lookup(alias)) {
      is SecretLookup.Found -> AliasState.FOUND
      SecretLookup.Missing -> AliasState.MISSING
      SecretLookup.WrongType -> AliasState.WRONG_TYPE
      SecretLookup.Invalid -> AliasState.INVALID_SECRET
    }
  }
}
