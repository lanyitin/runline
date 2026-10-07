package dev.lawlan.runline.engine.artifact

import dev.lawlan.runline.analyzer.Verdict
import java.nio.file.Path
import java.time.Instant

/**
 * One pipeline definition with what running it needs, found by version (content hash and uploader)
 * and pipeline name.
 */
data class StoredDefinition(
    /** Database identity; what runs refer to. */
    val id: Long,
    val contentHash: String,
    val uploadedBy: String,
    val className: String,
    val name: String,
    val metadata: MetadataDoc,
    val verdict: Verdict,
    /** The per-definition unsafe execution setting (ADR-006). */
    val allowUnsafeExecution: Boolean,
    val unsafeSettingSetBy: String?,
    val unsafeSettingSetAt: Instant?,
)

/** What running a stored pipeline needs from storage, and the administrator's unsafe setting. */
interface DefinitionStore {
  /**
   * The definition of pipeline [pipelineName] in the version [uploader] made of [contentHash], or
   * null. Whose version it is has been decided by the caller (see [VersionResolver]).
   */
  fun find(contentHash: String, uploader: String, pipelineName: String): StoredDefinition?

  /**
   * Records whether the definition may run when unsafe, with who decided and when. Returns the
   * definition as updated, or null when it does not exist.
   */
  fun setUnsafeExecution(
      contentHash: String,
      uploader: String,
      pipelineName: String,
      allow: Boolean,
      by: String,
      at: Instant,
  ): StoredDefinition?

  /**
   * Writes the jar of the content [contentHash] to [target]; false when there is no such content.
   */
  fun copyContent(contentHash: String, target: Path): Boolean
}
