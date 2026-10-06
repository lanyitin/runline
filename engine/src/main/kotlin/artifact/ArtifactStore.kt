package dev.lawlan.runline.engine.artifact

import dev.lawlan.runline.engine.auth.ApiIdentity
import dev.lawlan.runline.engine.auth.Role

/** Whose artifacts a query may see (ADR-012: developers see their own, administrators all). */
sealed interface Visibility {
  /** Whether what [uploader] uploaded may be seen. */
  fun permitsUploader(uploader: String): Boolean

  fun permits(artifact: ArtifactRecord): Boolean = permitsUploader(artifact.uploadedBy)

  data object All : Visibility {
    override fun permitsUploader(uploader: String) = true
  }

  data class OwnedBy(val uploader: String) : Visibility {
    override fun permitsUploader(uploader: String) = uploader == this.uploader
  }
}

/** What this caller may see: administrators everything, developers what they uploaded. */
val ApiIdentity.visibility: Visibility
  get() = if (role == Role.ADMIN) Visibility.All else Visibility.OwnedBy(name)

sealed interface SaveResult {
  /** The artifact and all its definitions were written. */
  data class Created(val artifact: ArtifactRecord) : SaveResult

  /** An artifact with the same content hash already exists; nothing was written. */
  data class AlreadyExists(val artifact: ArtifactRecord) : SaveResult

  /**
   * The allow list changed after the definitions were judged
   * ([NewArtifact.requiredAllowListVersion] is no longer the current version); nothing was written.
   * Judge again with the current list.
   */
  data object AllowListChanged : SaveResult
}

sealed interface DeleteResult {
  data object Deleted : DeleteResult

  data object NotFound : DeleteResult

  /** Something still references the artifact's definitions; nothing was deleted. */
  data object InUse : DeleteResult
}

/** Persistence of artifacts and their definitions. */
interface ArtifactStore {
  /**
   * Writes the artifact and all its definitions in one transaction, unless an artifact with the
   * same content hash exists (idempotent). A failure leaves no partial data.
   */
  fun saveIfAbsent(artifact: NewArtifact): SaveResult

  fun findByHash(contentHash: String): ArtifactRecord?

  fun list(visibility: Visibility): List<ArtifactRecord>

  /** Deletes the artifact with its definitions unless something references them. */
  fun delete(contentHash: String): DeleteResult
}
