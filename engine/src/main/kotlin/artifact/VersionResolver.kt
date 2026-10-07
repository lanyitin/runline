package dev.lawlan.runline.engine.artifact

/**
 * The result of finding the version a caller means by a content hash and an optional uploader
 * (ADR-020).
 */
sealed interface VersionOutcome<out T> {
  data class Resolved<T>(val value: T) : VersionOutcome<T>

  /** The caller can see several versions of the content and did not say whose they mean. */
  data class Ambiguous(val uploaders: List<String>) : VersionOutcome<Nothing>

  /** No such version, or one the caller may not see or use: the two are never told apart. */
  data object NotFound : VersionOutcome<Nothing>
}

inline fun <T, R> VersionOutcome<T>.map(transform: (T) -> R): VersionOutcome<R> =
    when (this) {
      is VersionOutcome.Resolved -> VersionOutcome.Resolved(transform(value))
      is VersionOutcome.Ambiguous -> this
      VersionOutcome.NotFound -> VersionOutcome.NotFound
    }

inline fun <T, R> VersionOutcome<T>.flatten(
    transform: (T) -> VersionOutcome<R>
): VersionOutcome<R> =
    when (this) {
      is VersionOutcome.Resolved -> transform(value)
      is VersionOutcome.Ambiguous -> this
      VersionOutcome.NotFound -> VersionOutcome.NotFound
    }

/**
 * Decides which version a request is about. The single place where the `uploader` a client sends is
 * checked against what the caller may see: only versions inside [Visibility] are candidates, so a
 * developer, who can see only their own, gets their own version or nothing whatever `uploader`
 * says, and an administrator, who can see all, is never given a version they did not ask for when
 * there is more than one (no preference for their own).
 */
class VersionResolver(private val store: ArtifactStore) {
  fun resolve(
      contentHash: String,
      requestedUploader: String?,
      visibility: Visibility,
  ): VersionOutcome<String> {
    val candidates =
        store
            .uploadersOf(contentHash)
            .filter { visibility.permitsUploader(it) }
            .filter { requestedUploader == null || it == requestedUploader }
    return when (candidates.size) {
      0 -> VersionOutcome.NotFound
      1 -> VersionOutcome.Resolved(candidates.single())
      else -> VersionOutcome.Ambiguous(candidates)
    }
  }
}
