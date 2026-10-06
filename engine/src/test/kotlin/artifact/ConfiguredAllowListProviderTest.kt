package dev.lawlan.runline.engine.artifact

import dev.lawlan.runline.analyzer.AllowListEntry
import kotlin.test.*

// Written after the implementation (it was needed to wire the upload tests first).
class ConfiguredAllowListProviderTest {
  @Test
  fun `provides the configured entries labelled as coming from configuration`() {
    val entries = listOf(AllowListEntry("java.lang"), AllowListEntry("kotlin", exactOnly = true))

    val allowList = ConfiguredAllowListProvider(entries).current()

    assertEquals("config", allowList.version)
    assertEquals(entries, allowList.entries)
  }

  @Test
  fun `is empty by default`() {
    assertEquals(emptyList(), ConfiguredAllowListProvider(emptyList()).current().entries)
  }
}
