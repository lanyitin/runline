package dev.lawlan.runline.core

/** Whether an IO action reads or changes things (existence checks and listings are reads). */
enum class IoAccess {
  READ,
  WRITE,
}

/**
 * Collects the IO a pipeline performs through its context, for the development entry's metadata
 * proposal. Safe to call from any thread.
 *
 * Memory is bounded. The first [maxEvents] events are kept one by one, in order. Every event, kept
 * or not, is also counted in a summary that has one entry per category, scope, access and
 * rejected-ness and, for the network and for processes, per host or command; for files the path is
 * left out of the summary, so any number of different paths adds nothing. What a proposal needs
 * (scope and access, hosts, commands) is therefore always complete, whatever the limit. The summary
 * grows only with the number of different hosts and commands, which is the size of the proposal
 * itself.
 *
 * Nothing recorded can hold secrets: files are named by scope and relative path only, the network
 * by host and port, processes by their command only.
 */
class IoRecorder(private val maxEvents: Int) {
  init {
    require(maxEvents >= 0) { "maxEvents must not be negative, was $maxEvents" }
  }

  private class Event(
      val sequence: Long,
      val category: IoCategory,
      val scope: FileScope?,
      val target: String,
      val port: Int?,
      val access: IoAccess,
      val rejected: Boolean,
  )

  private data class SummaryKey(
      val category: IoCategory,
      val scope: FileScope?,
      val target: String,
      val access: IoAccess,
      val rejected: Boolean,
  )

  private class Tally(val firstSequence: Long) {
    var count = 0L
    var lastSequence = firstSequence
  }

  private val lock = Any()
  private var total = 0L
  private val events = ArrayList<Event>()
  private val summary = LinkedHashMap<SummaryKey, Tally>()

  /**
   * Records one action. [target] is the relative path (files), the host (network) or the command
   * (processes); [scope] is for files, [port] for the network.
   */
  fun record(
      category: IoCategory,
      target: String,
      access: IoAccess,
      scope: FileScope? = null,
      port: Int? = null,
      rejected: Boolean = false,
  ) {
    synchronized(lock) {
      val sequence = ++total
      if (events.size < maxEvents) {
        events.add(Event(sequence, category, scope, target, port, access, rejected))
      }
      val key = SummaryKey(category, scope, summaryTarget(category, target), access, rejected)
      val tally = summary.getOrPut(key) { Tally(sequence) }
      tally.count++
      tally.lastSequence = sequence
    }
  }

  private fun summaryTarget(category: IoCategory, target: String): String =
      when (category) {
        IoCategory.FILE -> ""
        IoCategory.NETWORK -> target.lowercase()
        IoCategory.PROCESS -> target
      }

  /**
   * The recording as JDK types only (maps, lists, strings, numbers), so it can leave the run's
   * class loader: `maxEvents`, `total`, `events` (the kept events) and `summary`.
   */
  fun snapshot(): Map<String, Any?> =
      synchronized(lock) {
        val kept = events.map {
          mapOf(
              "sequence" to it.sequence,
              "category" to it.category.name,
              "scope" to it.scope?.name,
              "target" to it.target,
              "port" to it.port,
              "access" to it.access.name,
              "rejected" to it.rejected,
          )
        }
        val folded = summary.map { (key, tally) ->
          mapOf(
              "category" to key.category.name,
              "scope" to key.scope?.name,
              "target" to key.target,
              "access" to key.access.name,
              "rejected" to key.rejected,
              "count" to tally.count,
              "firstSequence" to tally.firstSequence,
              "lastSequence" to tally.lastSequence,
          )
        }
        java.util.LinkedHashMap<String, Any?>().apply {
          put("maxEvents", maxEvents)
          put("total", total)
          put("events", java.util.ArrayList(kept))
          put("summary", java.util.ArrayList(folded))
        }
      }
}
