package dev.lawlan.runline.engine.artifact

import dev.lawlan.runline.engine.auth.ApiIdentity
import dev.lawlan.runline.engine.auth.Role
import java.time.Instant

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

/** A trigger that still points at a version (name and kind, as the API shows the kind). */
data class TriggerReference(val name: String, val kind: String)

/** The runs of a version's definitions; [byState] has only the states that occur. */
data class RunImpact(
    val total: Long,
    val byState: Map<String, Long>,
    val active: Long,
    val earliestAt: Instant?,
    val latestAt: Instant?,
)

/** What deleting a version would take with it or be stopped by (ADR-047). */
data class DeletionImpact(
    val runs: RunImpact,
    val logEntries: Long,
    val triggers: List<TriggerReference>,
    /** Whether the jar goes too, that is no other uploader's version of the content remains. */
    val contentRemoved: Boolean,
) {
  val deletable: Boolean
    get() = triggers.isEmpty() && runs.active == 0L
}

sealed interface DeleteResult {
  /** The version went, with [runs] runs and [logEntries] log entries. */
  data class Deleted(val runs: Long, val logEntries: Long) : DeleteResult

  data object NotFound : DeleteResult

  /** Triggers still point at the version, or (none named) something else still references it. */
  data class InUse(val triggers: List<TriggerReference>) : DeleteResult

  /** [active] runs have not ended. */
  data class RunsActive(val active: Long) : DeleteResult

  /** The version has [total] ended runs and the caller did not confirm deleting them. */
  data class RunsExist(val total: Long) : DeleteResult

  /** The caller confirmed another number of runs than the [total] there are now. */
  data class ImpactChanged(val total: Long) : DeleteResult
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

  /** What deleting the version would take along or be stopped by, or null when there is none. */
  fun deletionImpact(contentHash: String, uploader: String): DeletionImpact?

  /**
   * Deletes the version with its definitions, in one transaction. Triggers or runs that have not
   * ended stop it; ended runs and their log go with it only when [confirmedRuns] equals their
   * number (ADR-047). The jar goes with the last version of its content. Deleting a version and
   * creating a run of it exclude each other.
   */
  fun delete(contentHash: String, uploader: String, confirmedRuns: Long? = null): DeleteResult
}
