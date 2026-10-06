package dev.lawlan.runline.devkit

import dev.lawlan.runline.core.FileMode
import dev.lawlan.runline.core.FileScope
import dev.lawlan.runline.core.IoAccess
import dev.lawlan.runline.core.IoCategory
import dev.lawlan.runline.runner.RecordedIo
import java.util.SortedSet

/**
 * The limits a recorded run would have needed, in the terms of a pipeline's declaration. A category
 * the run did not use is empty, which means "not allowed", never "unrestricted".
 */
data class MetadataProposal(
    val files: Map<FileScope, FileMode>,
    /** Lower case host names, without ports. */
    val hosts: SortedSet<String>,
    val commands: SortedSet<String>,
) {
  companion object {
    /** Derived from the summary, which is complete whatever the event limit was. */
    fun from(recording: RecordedIo): MetadataProposal {
      val accepted = recording.summary.filter { !it.rejected }
      fun targets(category: IoCategory) =
          accepted.filter { it.category == category }.map { it.target }
      val files =
          accepted
              .filter { it.category == IoCategory.FILE }
              .groupBy { it.scope!! }
              .mapValues { (_, uses) ->
                if (uses.any { it.access == IoAccess.WRITE }) FileMode.READ_WRITE
                else FileMode.READ_ONLY
              }
      return MetadataProposal(
          files,
          targets(IoCategory.NETWORK).map { it.lowercase() }.toSortedSet(),
          targets(IoCategory.PROCESS).toSortedSet(),
      )
    }
  }
}
