package dev.lawlan.runline.engine.allowlist

import dev.lawlan.runline.analyzer.AllowList
import dev.lawlan.runline.engine.artifact.AllowListProvider

/**
 * The allow list the administrator maintains, read from the database every time (nothing is kept in
 * memory, so any Engine process sees a change as soon as it is committed). Its version is the
 * version number of the stored list.
 */
class DatabaseAllowListProvider(private val store: AllowListStore) : AllowListProvider {
  override fun current(): AllowList =
      checkNotNull(store.current()) { "The allow list has not been initialised" }.toAllowList()
}

/** The list as the analyzer judges with it; the version is the number as text. */
fun AllowListSnapshot.toAllowList() = AllowList(number.toString(), entries.map { it.toEntry() })
