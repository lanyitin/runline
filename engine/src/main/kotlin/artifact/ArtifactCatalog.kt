package dev.lawlan.runline.engine.artifact

import dev.lawlan.runline.engine.auth.ApiIdentity
import org.slf4j.LoggerFactory

/**
 * Queries and removal of stored versions on behalf of an authenticated caller. Holds the rule that
 * a developer sees only what they uploaded while an administrator sees everything (ADR-012), and
 * that a content hash alone may name several versions for an administrator (ADR-020).
 */
class ArtifactCatalog(private val store: ArtifactStore, private val resolver: VersionResolver) {
  private val log = LoggerFactory.getLogger(ArtifactCatalog::class.java)

  fun list(caller: ApiIdentity): List<ArtifactRecord> = store.list(caller.visibility)

  /** The version [caller] means, which [uploader] narrows when the caller can see several. */
  fun find(
      contentHash: String,
      uploader: String?,
      caller: ApiIdentity,
  ): VersionOutcome<ArtifactRecord> =
      resolver.resolve(contentHash, uploader, caller.visibility).flatten {
        store.find(contentHash, it)?.let { found -> VersionOutcome.Resolved(found) }
            ?: VersionOutcome.NotFound
      }

  /** What deleting the version [caller] means would take along or be stopped by. */
  fun deletionImpact(
      contentHash: String,
      uploader: String?,
      caller: ApiIdentity,
  ): VersionOutcome<DeletionImpact> =
      resolver.resolve(contentHash, uploader, caller.visibility).flatten { owner ->
        store.deletionImpact(contentHash, owner)?.let { VersionOutcome.Resolved(it) }
            ?: VersionOutcome.NotFound
      }

  /** Deletes the version; [confirmedRuns] is the number of runs the caller agreed to delete too. */
  fun delete(
      contentHash: String,
      uploader: String?,
      caller: ApiIdentity,
      confirmedRuns: Long? = null,
  ): VersionOutcome<DeleteResult> =
      resolver.resolve(contentHash, uploader, caller.visibility).map { owner ->
        store.delete(contentHash, owner, confirmedRuns).also {
          audit(it, contentHash, owner, caller)
        }
      }

  private fun audit(result: DeleteResult, contentHash: String, owner: String, caller: ApiIdentity) {
    when (result) {
      is DeleteResult.Deleted ->
          log.info(
              "Version deleted by {}: contentHash={} uploader={} runs={} logEntries={}",
              caller.name,
              contentHash,
              owner,
              result.runs,
              result.logEntries,
          )
      DeleteResult.NotFound -> {}
      else ->
          log.info(
              "Deletion of a version refused for {}: contentHash={} uploader={} reason={}",
              caller.name,
              contentHash,
              owner,
              result.reason(),
          )
    }
  }

  private fun DeleteResult.reason() =
      when (this) {
        is DeleteResult.InUse -> "in_use"
        is DeleteResult.RunsActive -> "runs_active"
        is DeleteResult.RunsExist -> "runs_exist"
        is DeleteResult.ImpactChanged -> "impact_changed"
        else -> error("not a refusal: $this")
      }
}
