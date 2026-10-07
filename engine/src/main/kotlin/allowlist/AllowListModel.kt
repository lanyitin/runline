package dev.lawlan.runline.engine.allowlist

import dev.lawlan.runline.analyzer.AllowListEntry
import dev.lawlan.runline.analyzer.ClassEntry
import dev.lawlan.runline.analyzer.PackageEntry
import dev.lawlan.runline.analyzer.Verdict
import dev.lawlan.runline.engine.artifact.ReasonDoc
import java.time.Instant

/** The two kinds of allow list entry; kept apart by this field, never by guessing at a name. */
enum class EntryKind {
  PACKAGE,
  CLASS,
}

/** One entry of the stored allow list, with who last changed it. */
data class StoredEntry(
    val kind: EntryKind,
    val name: String,
    /** "This package only"; always false for a class. */
    val exactOnly: Boolean,
    val createdBy: String,
    val createdAt: Instant,
    val updatedBy: String,
    val updatedAt: Instant,
) {
  /** The entry as the analyzer judges with it. */
  fun toEntry(): AllowListEntry =
      when (kind) {
        EntryKind.PACKAGE -> PackageEntry(name, exactOnly)
        EntryKind.CLASS -> ClassEntry(name)
      }
}

/** The entry as a kind and a name, which is what identifies it. */
val AllowListEntry.kind: EntryKind
  get() =
      when (this) {
        is PackageEntry -> EntryKind.PACKAGE
        is ClassEntry -> EntryKind.CLASS
      }

val AllowListEntry.entryName: String
  get() =
      when (this) {
        is PackageEntry -> packageName
        is ClassEntry -> className
      }

/** What changed an allow list version. */
enum class VersionAction {
  /** The first content, from the default list or from configuration. */
  INITIAL,
  ENTRY_ADDED,
  ENTRY_CHANGED,
  ENTRY_REMOVED,
}

/** A version of the allow list: what was done, by whom, and what it did to the verdicts. */
data class AllowListVersion(
    val version: Long,
    val changedBy: String,
    val changedAt: Instant,
    val action: VersionAction,
    val detail: String,
    /** How many definitions were judged again for this version. */
    val rejudgedDefinitions: Int = 0,
    val becameUnsafe: Int = 0,
    val becameSafe: Int = 0,
)

/** The allow list in force: its version and entries (in the order they were added). */
data class AllowListSnapshot(
    val version: AllowListVersion,
    val entries: List<StoredEntry>,
) {
  val number: Long
    get() = version.version
}

/** A stored version (a jar and its uploader), as far as judging it again needs. */
data class ArtifactRef(val id: Long, val contentHash: String, val uploader: String)

/** A definition stored with a verdict, which a new allow list may change. */
data class JudgedDefinition(
    val id: Long,
    val className: String,
    val name: String,
    val verdict: Verdict,
    val allowUnsafeExecution: Boolean,
)

/** The result of judging a stored definition again, to be written. */
data class NewJudgement(
    val verdict: Verdict,
    val reasons: List<ReasonDoc>,
    val allowListVersion: String,
    /**
     * The definition became unsafe: it may no longer run as unsafe until an administrator allows.
     */
    val revokeUnsafeExecution: Boolean,
    val by: String,
    val at: Instant,
)
