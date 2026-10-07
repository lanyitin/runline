package dev.lawlan.runline.engine.allowlist

import dev.lawlan.runline.analyzer.Verdict

/** What an administrator asks of the allow list. Names are as typed; they are validated. */
sealed interface AllowListChange {
  data class Add(val kind: EntryKind, val name: String, val exactOnly: Boolean = false) :
      AllowListChange

  /** Renames an entry and/or sets its "this package only" flag; at least one must be given. */
  data class Modify(
      val kind: EntryKind,
      val name: String,
      val newName: String? = null,
      val exactOnly: Boolean? = null,
  ) : AllowListChange

  data class Remove(val kind: EntryKind, val name: String) : AllowListChange

  /** Judges every stored definition again with the current list and the current analysis rules. */
  data object Recheck : AllowListChange
}

/** Whether a change is carried out or only looked at. */
enum class ChangeMode {
  APPLY,
  PREVIEW,
}

/** A stored definition whose verdict a change flips. */
data class VerdictChange(
    val contentHash: String,
    /** Whose version of the content; each version is judged and reported on its own (ADR-020). */
    val uploader: String,
    val pipeline: String,
    val className: String,
    val from: Verdict,
    val to: Verdict,
    /** The definition's "may run when unsafe" setting before the change. */
    val allowUnsafeExecution: Boolean,
    /**
     * The definition became unsafe while it was allowed to run so: that permission is withdrawn.
     */
    val unsafeExecutionRevoked: Boolean,
)

/** What judging every stored definition again with a list does (or, in a preview, would do). */
data class Impact(
    val examinedArtifacts: Int,
    val examinedDefinitions: Int,
    /** Only the definitions whose verdict flips. */
    val changes: List<VerdictChange>,
    /** Definitions that could not be read again and were judged unsafe for that reason. */
    val unreadable: Int,
) {
  val becameUnsafe: Int
    get() = changes.count { it.to == Verdict.UNSAFE }

  val becameSafe: Int
    get() = changes.count { it.to == Verdict.SAFE }
}

enum class InvalidChange {
  /** The name is not a valid package or class name. */
  NAME,

  /** "This package only" makes no sense for a class entry. */
  EXACT_ONLY_ON_CLASS,

  /** A modification that gives nothing to change. */
  NOTHING_TO_CHANGE,
}

sealed interface ChangeResult {
  /** The change was made as [version]; [entry] is the entry as it is now (null for a removal). */
  data class Applied(
      val version: AllowListVersion?,
      val entry: StoredEntry?,
      val impact: Impact,
      /** Other entries the new one makes unnecessary; they were left in place. */
      val redundant: List<StoredEntry>,
  ) : ChangeResult

  /** Nothing was changed; this is what the change would do. */
  data class Previewed(
      val currentVersion: Long,
      val impact: Impact,
      val redundant: List<StoredEntry>,
  ) : ChangeResult

  data class Invalid(val problem: InvalidChange, val message: String) : ChangeResult

  /** The same kind and name is already an entry (change it instead). */
  data class Duplicate(val existing: StoredEntry) : ChangeResult

  /** An existing entry already trusts everything the requested one would. */
  data class Covered(val by: StoredEntry) : ChangeResult

  data object NotFound : ChangeResult
}
