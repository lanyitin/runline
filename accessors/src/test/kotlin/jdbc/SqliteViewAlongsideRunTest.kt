package dev.lawlan.runline.accessors.jdbc

import dev.lawlan.runline.accessors.ReadOnlyQuery
import dev.lawlan.runline.accessors.ReadOnlyRows
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A view reads the file a run writes (ADR-033, WI-1301): with no run there is, while one holds the
 * write lock, and while runs write all the time, and in none of them does it get in the way of a
 * run or leave the file in a state the Engine's account cannot use (runbook section 8).
 */
class SqliteViewAlongsideRunTest {
  private val rig = SqliteRig()

  @AfterTest fun close() = rig.close()

  private fun read(sql: String = "SELECT a FROM t", limit: Int = 200): ReadOnlyRows =
      JdbcViewRead(
              rig.profiles.find("sqlite")!!,
              rig.settings(),
              JdbcCredential.None,
              ReadOnlyQuery(sql, emptyMap(), listOf("a"), emptyList(), limit, 0),
          )
          .run()

  private fun files() =
      Files.list(rig.root).use { list -> list.map { it.fileName.toString() }.sorted().toList() }

  @Test
  fun `with no run there is, the file is read and left as the runs left it`() {
    val host = rig.host()
    rig.update(host, "CREATE TABLE t (a INTEGER)")
    rig.update(host, "INSERT INTO t VALUES (1), (2)")
    host.invalidateAll(dev.lawlan.runline.accessors.Invalidation.RUN_ENDED)
    val before = files()

    val page = read()

    assertEquals(listOf(listOf<Any?>(1L), listOf(2L)), page.rows)
    assertEquals(before, files())
    assertEquals(listOf("digest.db"), files())
    // The next run opens it as before and writes.
    val next = rig.host()
    assertEquals(1L, rig.update(next, "INSERT INTO t VALUES (3)"))
  }

  @Test
  fun `while a run holds the write lock the read sees what is committed and the run commits`() {
    val host = rig.host()
    rig.update(host, "CREATE TABLE t (a INTEGER)")
    rig.update(host, "INSERT INTO t VALUES (1)")
    rig.value(host, "jdbc.begin")
    rig.update(host, "INSERT INTO t VALUES (2)")

    val duringWrite = read()
    val committed = System.nanoTime()
    rig.value(host, "jdbc.commit")
    val commitMillis = (System.nanoTime() - committed) / 1_000_000

    assertEquals(listOf(listOf<Any?>(1L)), duringWrite.rows)
    assertTrue(commitMillis < 2_000, "the commit took $commitMillis ms")
    assertEquals(2, read().rows.size)
  }

  @Test
  fun `runs that write all the time all succeed while reads go on`() {
    val host = rig.host()
    rig.update(host, "CREATE TABLE t (a INTEGER)")
    val writer = Executors.newSingleThreadExecutor()
    val readers = Executors.newFixedThreadPool(4)
    try {
      val writes =
          writer.submit<Int> {
            repeat(300) { n ->
              rig.value(host, "jdbc.begin")
              rig.update(host, "INSERT INTO t VALUES (?)", listOf(n.toLong()))
              rig.value(host, "jdbc.commit")
            }
            300
          }
      val reads = (1..40).map { readers.submit<Int> { read().rows.size } }

      val seen = reads.map { it.get(60, TimeUnit.SECONDS) }

      assertEquals(300, writes.get(60, TimeUnit.SECONDS))
      assertTrue(seen.all { it in 0..200 })
      assertEquals(200, read().rows.size)
      assertEquals(300L, rig.query(host, "SELECT count(*) AS c FROM t").single()["c"])
    } finally {
      writer.shutdownNow()
      readers.shutdownNow()
    }
  }
}
