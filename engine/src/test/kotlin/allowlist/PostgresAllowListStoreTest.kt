package dev.lawlan.runline.engine.allowlist

import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.support.migratedDatabase
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** The allow list's persistence against a real PostgreSQL with the real migrations. */
class PostgresAllowListStoreTest {
  private val store = PostgresAllowListStore(dataSourceOf(migratedDatabase()))
  private val at = Instant.parse("2026-10-05T10:00:00Z")

  private fun AllowListSession.seed(version: Long = 1) {
    insertEntry(EntryKind.PACKAGE, "java.lang", exactOnly = true, "root", at)
    insertEntry(EntryKind.CLASS, "java.io.PrintStream", exactOnly = false, "root", at)
    appendVersion(AllowListVersion(version, "root", at, VersionAction.INITIAL, "initial"))
  }

  @Test
  fun `a new database has no allow list`() {
    assertNull(store.current())
    assertEquals(emptyList(), store.versions(10))
  }

  @Test
  fun `a change is readable afterwards, entries in the order they were added`() {
    store.change { it.seed() }

    val snapshot = assertNotNull(store.current())
    assertEquals(1, snapshot.number)
    assertEquals("root", snapshot.version.changedBy)
    assertEquals(at, snapshot.version.changedAt)
    assertEquals(
        listOf(
            Triple(EntryKind.PACKAGE, "java.lang", true),
            Triple(EntryKind.CLASS, "java.io.PrintStream", false),
        ),
        snapshot.entries.map { Triple(it.kind, it.name, it.exactOnly) },
    )
    assertEquals("root", snapshot.entries.first().createdBy)
  }

  @Test
  fun `the same name can be a package entry and a class entry, a kind and name only once`() {
    store.change {
      it.insertEntry(EntryKind.PACKAGE, "a.b", false, "root", at)
      it.insertEntry(EntryKind.CLASS, "a.b", false, "root", at)
      it.appendVersion(AllowListVersion(1, "root", at, VersionAction.INITIAL, "x"))
    }

    assertFails { store.change { it.insertEntry(EntryKind.PACKAGE, "a.b", true, "root", at) } }
    assertEquals(2, store.current()!!.entries.size)
  }

  @Test
  fun `updating and deleting an entry keeps who created it and says whether it existed`() {
    store.change { it.seed() }
    val later = at.plusSeconds(60)

    val results = store.change {
      listOf(
          it.updateEntry(EntryKind.PACKAGE, "java.lang", "java.lang", false, "ann", later),
          it.updateEntry(EntryKind.PACKAGE, "no.such", "no.such", false, "ann", later),
          it.deleteEntry(EntryKind.CLASS, "java.io.PrintStream"),
          it.deleteEntry(EntryKind.CLASS, "java.io.PrintStream"),
      )
    }

    assertEquals(listOf(true, false, true, false), results)
    val entry = store.current()!!.entries.single()
    assertEquals("java.lang", entry.name)
    assertFalse(entry.exactOnly)
    assertEquals("root", entry.createdBy)
    assertEquals("ann", entry.updatedBy)
    assertEquals(later, entry.updatedAt)
  }

  @Test
  fun `a failing change leaves the entries and versions as they were`() {
    store.change { it.seed() }

    assertFailsWith<IllegalStateException> {
      store.change {
        it.deleteEntry(EntryKind.PACKAGE, "java.lang")
        it.insertEntry(EntryKind.PACKAGE, "kotlin", false, "root", at)
        it.appendVersion(AllowListVersion(2, "root", at, VersionAction.ENTRY_ADDED, "x"))
        error("boom")
      }
    }

    val snapshot = store.current()!!
    assertEquals(1, snapshot.number)
    assertEquals(listOf("java.lang", "java.io.PrintStream"), snapshot.entries.map { it.name })
  }

  @Test
  fun `versions are numbered from what the session sees and listed newest first`() {
    store.change { it.seed() }
    val next = store.change { it.nextVersion() }
    store.change {
      it.appendVersion(
          AllowListVersion(
              next,
              "ann",
              at.plusSeconds(1),
              VersionAction.ENTRY_ADDED,
              "kotlin",
              3,
              1,
              0,
          )
      )
    }

    assertEquals(2, next)
    val versions = store.versions(10)
    assertEquals(listOf(2L, 1L), versions.map { it.version })
    assertEquals(
        Triple(3, 1, 0),
        Triple(versions[0].rejudgedDefinitions, versions[0].becameUnsafe, versions[0].becameSafe),
    )
    assertEquals(listOf(2L), store.versions(1).map { it.version })
  }

  @Test
  fun `a read-only view cannot write`() {
    store.change { it.seed() }

    assertFails { store.read { it.insertEntry(EntryKind.PACKAGE, "kotlin", false, "root", at) } }
    assertEquals(2, store.current()!!.entries.size)
    assertEquals(2, store.read { it.snapshot()!!.entries.size })
  }

  @Test
  fun `concurrent changes wait for each other, so each sees the version the last one left`() {
    store.change { it.seed() }
    val workers = 8
    val pool = Executors.newFixedThreadPool(workers)
    val ready = CountDownLatch(workers)
    val go = CountDownLatch(1)
    try {
      val futures =
          (1..workers).map { n ->
            pool.submit<Long> {
              ready.countDown()
              go.await()
              store.change {
                val version = it.nextVersion()
                it.insertEntry(EntryKind.PACKAGE, "p$n", false, "w$n", at)
                it.appendVersion(
                    AllowListVersion(version, "w$n", at, VersionAction.ENTRY_ADDED, "p$n")
                )
                version
              }
            }
          }
      assertTrue(ready.await(10, TimeUnit.SECONDS))
      go.countDown()
      val versions = futures.map { it.get(30, TimeUnit.SECONDS) }

      assertEquals((2L..9L).toList(), versions.sorted())
      assertEquals(9, store.current()!!.number)
      assertEquals(2 + workers, store.current()!!.entries.size)
    } finally {
      pool.shutdownNow()
    }
  }
}
