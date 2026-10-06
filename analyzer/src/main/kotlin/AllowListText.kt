package dev.lawlan.runline.analyzer

/** One item of an allow list text that could not be read: the item as written and why. */
data class AllowListTextProblem(val entry: String, val reason: String)

/**
 * What reading an allow list text gave: the entries that are valid and a problem for each other.
 */
data class AllowListParse(
    val entries: List<AllowListEntry>,
    val problems: List<AllowListTextProblem>,
)

/**
 * The one text form of an allow list (WI-25): comma separated items; `package`, `package:exact`
 * ("this package only") and `class:full.Name`. Reading and writing live only here; the Engine's
 * configuration and the development entry point use it, so both accept and reject the same text.
 */
object AllowListText {
  private const val SEPARATOR = ','
  private const val EXACT_SUFFIX = ":exact"
  private const val OLD_EXACT_MARK = "!"

  /** [entries] in the text form, which [parse] reads back as the same list. */
  fun format(entries: List<AllowListEntry>): String =
      entries.joinToString(SEPARATOR.toString()) { formatEntry(it) }

  fun formatEntry(entry: AllowListEntry): String =
      when (entry) {
        is ClassEntry -> ClassEntry.TOKEN_PREFIX + entry.className
        is PackageEntry -> entry.packageName + if (entry.exactOnly) EXACT_SUFFIX else ""
      }

  /** Reads every item; blank items are skipped, the others are an entry or a problem. */
  fun parse(text: String): AllowListParse {
    val entries = mutableListOf<AllowListEntry>()
    val problems = mutableListOf<AllowListTextProblem>()
    text
        .split(SEPARATOR)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .forEach { item ->
          try {
            entries += parseEntry(item)
          } catch (e: IllegalArgumentException) {
            problems += AllowListTextProblem(item, e.message ?: "invalid entry")
          }
        }
    return AllowListParse(entries, problems)
  }

  private fun parseEntry(item: String): AllowListEntry {
    require(!item.endsWith(OLD_EXACT_MARK)) {
      "a trailing '!' is not supported; write the package as 'name$EXACT_SUFFIX' for \"this package only\""
    }
    return when {
      item.startsWith(ClassEntry.TOKEN_PREFIX) ->
          ClassEntry(item.removePrefix(ClassEntry.TOKEN_PREFIX).trim())
      item.endsWith(EXACT_SUFFIX) ->
          PackageEntry(item.removeSuffix(EXACT_SUFFIX).trim(), exactOnly = true)
      else -> PackageEntry(item)
    }
  }
}
