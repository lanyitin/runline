package dev.lawlan.runline.runner

import dev.lawlan.runline.core.FileScope
import dev.lawlan.runline.core.IoAccess
import dev.lawlan.runline.core.IoCategory

/**
 * One recorded action. Files are named by scope and relative path; see
 * [dev.lawlan.runline.core.IoRecorder].
 */
data class RecordedEvent(
    val sequence: Long,
    val category: IoCategory,
    /** For files only. */
    val scope: FileScope?,
    /** Relative path (files), host (network) or command (processes). */
    val target: String,
    /** For the network only. */
    val port: Int?,
    val access: IoAccess,
    /** Refused by a boundary that recording does not relax. */
    val rejected: Boolean,
)

/** Every event of one kind folded together, whether or not it was kept as an event. */
data class RecordedSummary(
    val category: IoCategory,
    val scope: FileScope?,
    /** Host (lower case) or command; empty for files. */
    val target: String,
    val access: IoAccess,
    val rejected: Boolean,
    val count: Long,
    val firstSequence: Long,
    val lastSequence: Long,
)

/** What a recording run did, as the host sees it: built from JDK types only. */
data class RecordedIo(
    val maxEvents: Int,
    /** Every action, kept or not. */
    val total: Long,
    /** The first [maxEvents] actions, in order. */
    val events: List<RecordedEvent>,
    val summary: List<RecordedSummary>,
) {
  companion object {
    @Suppress("UNCHECKED_CAST")
    internal fun fromBoundary(snapshot: Map<String, Any?>): RecordedIo =
        RecordedIo(
            maxEvents = snapshot["maxEvents"] as Int,
            total = snapshot["total"] as Long,
            events =
                (snapshot["events"] as List<Map<String, Any?>>).map {
                  RecordedEvent(
                      it["sequence"] as Long,
                      IoCategory.valueOf(it["category"] as String),
                      (it["scope"] as String?)?.let(FileScope::valueOf),
                      it["target"] as String,
                      it["port"] as Int?,
                      IoAccess.valueOf(it["access"] as String),
                      it["rejected"] as Boolean,
                  )
                },
            summary =
                (snapshot["summary"] as List<Map<String, Any?>>).map {
                  RecordedSummary(
                      IoCategory.valueOf(it["category"] as String),
                      (it["scope"] as String?)?.let(FileScope::valueOf),
                      it["target"] as String,
                      IoAccess.valueOf(it["access"] as String),
                      it["rejected"] as Boolean,
                      it["count"] as Long,
                      it["firstSequence"] as Long,
                      it["lastSequence"] as Long,
                  )
                },
        )
  }
}
