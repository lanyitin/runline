package dev.lawlan.runline.engine.artifact

import dev.lawlan.runline.engine.auth.ApiIdentity
import org.slf4j.LoggerFactory

/**
 * Queries and removal of stored artifacts on behalf of an authenticated caller. Holds the rule that
 * a developer sees only what they uploaded while an administrator sees everything (ADR-012).
 */
class ArtifactCatalog(private val store: ArtifactStore) {
  private val log = LoggerFactory.getLogger(ArtifactCatalog::class.java)

  fun list(caller: ApiIdentity): List<ArtifactRecord> = store.list(caller.visibility)

  /** The artifact, or null when it does not exist or [caller] may not see it. */
  fun find(contentHash: String, caller: ApiIdentity): ArtifactRecord? =
      store.findByHash(contentHash)?.takeIf { caller.visibility.permits(it) }

  fun delete(contentHash: String, caller: ApiIdentity): DeleteResult =
      store.delete(contentHash).also {
        if (it == DeleteResult.Deleted)
            log.info("Artifact {} deleted by {}", contentHash, caller.name)
      }
}
