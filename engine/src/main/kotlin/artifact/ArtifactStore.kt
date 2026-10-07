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
  /** The version and all its definitions were written. */
  data class Created(val artifact: ArtifactRecord) : SaveResult

  /** The uploader already has a version of this content; nothing was written. */
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

  /** Something still references the version's definitions; nothing was deleted. */
  data object InUse : DeleteResult
}

/**
 * Persistence of versions (a content hash and the uploader) and their definitions. The jar itself
 * is kept once per content hash and belongs to no version in particular (ADR-020).
 */
interface ArtifactStore {
  /**
   * Writes the uploader's version with all its definitions in one transaction, unless that uploader
   * already has a version of the same content (idempotent). Other uploaders' versions of the same
   * content do not matter. A failure leaves no partial data.
   */
  fun saveIfAbsent(artifact: NewArtifact): SaveResult

  fun find(contentHash: String, uploader: String): ArtifactRecord?

  /** Who has a version of [contentHash], oldest version first. */
  fun uploadersOf(contentHash: String): List<String>

  fun list(visibility: Visibility): List<ArtifactRecord>

  /**
   * Deletes the version with its definitions unless something references them. The jar goes with
   * the last version of its content, in the same transaction.
   */
  fun delete(contentHash: String, uploader: String): DeleteResult
}
