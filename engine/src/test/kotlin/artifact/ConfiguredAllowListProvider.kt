package dev.lawlan.runline.engine.artifact

import dev.lawlan.runline.analyzer.AllowList
import dev.lawlan.runline.analyzer.AllowListEntry

/**
 * A fixed allow list, labelled `config`, for tests of what sits below the database-managed list
 * (the run machinery); the Engine itself uses the administered list.
 */
class ConfiguredAllowListProvider(entries: List<AllowListEntry>) : AllowListProvider {
  private val allowList = AllowList(VERSION, entries.toList())

  override fun current(): AllowList = allowList

  companion object {
    const val VERSION = "config"
  }
}
