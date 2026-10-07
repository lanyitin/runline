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

  fun delete(
      contentHash: String,
      uploader: String?,
      caller: ApiIdentity,
  ): VersionOutcome<DeleteResult> =
      resolver.resolve(contentHash, uploader, caller.visibility).map { owner ->
        store.delete(contentHash, owner).also {
          if (it == DeleteResult.Deleted)
              log.info("Version {} of {} deleted by {}", owner, contentHash, caller.name)
        }
      }
}
