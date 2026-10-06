package dev.lawlan.runline.engine.allowlist

import java.nio.file.Path
import java.time.Instant

/**
 * Everything one allow list change (or a look at what it would do) touches, in one database
 * transaction: the entries, the version history, and the stored jars and verdicts that are judged
 * again with the new list.
 */
interface AllowListSession {
  /** The allow list as this session sees it, or null when it has never been initialised. */
  fun snapshot(): AllowListSnapshot?

  /** The number the next version gets; counted from the version this session sees. */
  fun nextVersion(): Long

  fun insertEntry(kind: EntryKind, name: String, exactOnly: Boolean, by: String, at: Instant)

  /** Changes an entry in place (renaming it, or its "this package only" flag); false if absent. */
  fun updateEntry(
      kind: EntryKind,
      name: String,
      newName: String,
      exactOnly: Boolean,
      by: String,
      at: Instant,
  ): Boolean

  /** False when there is no such entry. */
  fun deleteEntry(kind: EntryKind, name: String): Boolean

  fun appendVersion(version: AllowListVersion)

  fun artifacts(): List<ArtifactRef>

  /** Writes the jar of [artifactId] to [target]. */
  fun copyArtifact(artifactId: Long, target: Path)

  fun definitionsOf(artifactId: Long): List<JudgedDefinition>

  /** Writes a new verdict, its reasons and the version it was judged under for a definition. */
  fun rejudge(definitionId: Long, judgement: NewJudgement)
}

/** Persistence of the allow list. */
interface AllowListStore {
  /** The allow list in force, or null before it has been initialised. */
  fun current(): AllowListSnapshot?

  /** Newest first. */
  fun versions(limit: Int): List<AllowListVersion>

  /**
   * Runs [block] in one transaction that holds the allow list's exclusive lock, so changes (and
   * uploads that are about to store a verdict) wait for each other. Everything [block] wrote is
   * undone when it throws.
   */
  fun <T> change(block: (AllowListSession) -> T): T

  /**
   * Runs [block] on a consistent read-only view that takes no lock; any write fails. Used to look
   * at what a change would do.
   */
  fun <T> read(block: (AllowListSession) -> T): T
}
