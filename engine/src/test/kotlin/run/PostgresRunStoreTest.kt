package dev.lawlan.runline.engine.run

import dev.lawlan.runline.analyzer.Verdict
import dev.lawlan.runline.engine.artifact.*
import dev.lawlan.runline.engine.db.dataSourceOf
import dev.lawlan.runline.engine.support.StoredPipelines
import dev.lawlan.runline.engine.support.migratedDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.*

class PostgresRunStoreTest {
  private val dataSource = dataSourceOf(migratedDatabase())
  private val dir: Path = Files.createTempDirectory("run-store")
  private val artifacts = PostgresArtifactStore(dataSource)
  private val pipelines = StoredPipelines(artifacts, dir)
  private val definitions = PostgresDefinitionStore(dataSource)
  private val store = PostgresRunStore(dataSource)
  private val t0 = Instant.now().truncatedTo(ChronoUnit.MICROS)

  private fun definitionId(hash: String, name: String, uploader: String = "alice") =
      definitions.find(hash, uploader, name)!!.id

  private fun newRun(
      definitionId: Long,
      id: UUID = UUID.randomUUID(),
      source: RunSource = RunSource.Manual("alice"),
      parameters: Map<String, String> = mapOf("env" to "prod"),
      createdAt: Instant = t0,
      unsafe: UnsafeExecution? = null,
  ) = NewRun(id, definitionId, source, parameters, createdAt, unsafe)

  private fun queued(
      hash: String = pipelines.save("v1", "nightly"),
      name: String = "nightly",
      uploader: String = "alice",
  ) = store.insert(newRun(definitionId(hash, name, uploader)))

  // ---- insert and find ----

  @Test
  fun `a new run is queued and carries what it was created from`() {
    val hash = pipelines.save("v1", "nightly")

    val run =
        store.insert(
            newRun(
                definitionId(hash, "nightly"),
                source = RunSource.Manual("alice"),
                parameters = mapOf("env" to "prod", "retries" to "3"),
            )
        )

    assertEquals(RunState.QUEUED, run.state)
    assertEquals(hash, run.contentHash)
    assertEquals("nightly", run.pipelineName)
    assertEquals("p.nightly", run.className)
    assertEquals(RunSource.Manual("alice"), run.source)
    assertEquals(mapOf("env" to "prod", "retries" to "3"), run.parameters)
    assertEquals(t0, run.createdAt)
    assertNull(run.startedAt)
    assertNull(run.finishedAt)
    assertNull(run.failure)
    assertNull(run.unsafeExecution)
    assertEquals(run, store.find(run.id, Visibility.All))
  }

  @Test
  fun `a run caused by a trigger records the trigger as its source`() {
    val hash = pipelines.save("v1", "nightly")

    val run = store.insert(newRun(definitionId(hash, "nightly"), source = RunSource.Trigger("t-1")))

    assertEquals(RunSource.Trigger("t-1"), store.find(run.id, Visibility.All)!!.source)
  }

  @Test
  fun `an unsafe run records the setting and who set it`() {
    val hash = pipelines.save("v1", "risky", verdict = Verdict.UNSAFE)
    val setting = UnsafeExecution("root", t0.minusSeconds(60))

    val run = store.insert(newRun(definitionId(hash, "risky"), unsafe = setting))

    assertEquals(setting, store.find(run.id, Visibility.All)!!.unsafeExecution)
  }

  @Test
  fun `a run id is unique`() {
    val id = definitionId(pipelines.save("v1", "nightly"), "nightly")
    val runId = UUID.randomUUID()
    store.insert(newRun(id, runId))

    assertFailsWith<SQLException> { store.insert(newRun(id, runId)) }
  }

  @Test
  fun `a run needs an existing definition`() {
    assertFailsWith<SQLException> { store.insert(newRun(definitionId = 987_654)) }
  }

  @Test
  fun `a run that does not exist is not found`() {
    assertNull(store.find(UUID.randomUUID(), Visibility.All))
  }

  // ---- visibility ----

  @Test
  fun `a run of another uploader's pipeline is not found under owner visibility`() {
    val mine = queued(pipelines.save("v1", "mine", uploader = "alice"), "mine")
    val theirs = queued(pipelines.save("v2", "theirs", uploader = "bob"), "theirs", "bob")

    assertNotNull(store.find(mine.id, Visibility.OwnedBy("alice")))
    assertNull(store.find(theirs.id, Visibility.OwnedBy("alice")))
    assertNotNull(store.find(theirs.id, Visibility.All))
  }

  @Test
  fun `listing is newest first, filtered by visibility and pipeline, and limited`() {
    val alice1 = pipelines.save("v1", "alpha", uploader = "alice")
    val alice2 = pipelines.save("v2", "beta", uploader = "alice")
    val bob = pipelines.save("v3", "gamma", uploader = "bob")
    val a = store.insert(newRun(definitionId(alice1, "alpha"), createdAt = t0))
    val b = store.insert(newRun(definitionId(alice2, "beta"), createdAt = t0.plusSeconds(1)))
    val c = store.insert(newRun(definitionId(bob, "gamma", "bob"), createdAt = t0.plusSeconds(2)))
    val d = store.insert(newRun(definitionId(alice1, "alpha"), createdAt = t0.plusSeconds(3)))

    assertEquals(listOf(d.id, c.id, b.id, a.id), store.list(Visibility.All, null, 10).map { it.id })
    assertEquals(
        listOf(d.id, b.id, a.id),
        store.list(Visibility.OwnedBy("alice"), null, 10).map { it.id },
    )
    assertEquals(listOf(d.id, a.id), store.list(Visibility.All, "alpha", 10).map { it.id })
    assertEquals(listOf(d.id, c.id), store.list(Visibility.All, null, 2).map { it.id })
  }

  // ---- state changes ----

  @Test
  fun `a run advances forward and the start is recorded when it initializes`() {
    val run = queued()
    val at = t0.plusSeconds(5)

    assertTrue(store.advance(run.id, RunState.INITIALIZING, at))
    assertTrue(store.advance(run.id, RunState.RUNNING, at.plusSeconds(1)))

    val stored = store.find(run.id, Visibility.All)!!
    assertEquals(RunState.RUNNING, stored.state)
    assertEquals(at, stored.startedAt)
  }

  @Test
  fun `a run never moves backward or stays where it is`() {
    val run = queued()
    store.advance(run.id, RunState.RUNNING, t0)

    assertFalse(store.advance(run.id, RunState.QUEUED, t0))
    assertFalse(store.advance(run.id, RunState.INITIALIZING, t0))
    assertFalse(store.advance(run.id, RunState.RUNNING, t0))
    assertEquals(RunState.RUNNING, store.find(run.id, Visibility.All)!!.state)
  }

  @Test
  fun `finishing records the end, the failure and cannot be repeated or undone`() {
    val run = queued()
    store.advance(run.id, RunState.RUNNING, t0)
    val failure = FailureInfo("java.lang.IllegalStateException", "boom", "trace")
    val end = t0.plusSeconds(9)

    assertTrue(store.finish(run.id, RunState.FAILED, end, failure))
    assertFalse(store.finish(run.id, RunState.SUCCEEDED, end.plusSeconds(1)))
    assertFalse(store.advance(run.id, RunState.RUNNING, end))

    val stored = store.find(run.id, Visibility.All)!!
    assertEquals(RunState.FAILED, stored.state)
    assertEquals(end, stored.finishedAt)
    assertEquals(failure, stored.failure)
  }

  @Test
  fun `a run that never started can be finished`() {
    val run = queued()

    assertTrue(store.finish(run.id, RunState.CANCELLED, t0))

    assertEquals(RunState.CANCELLED, store.find(run.id, Visibility.All)!!.state)
  }

  @Test
  fun `a failure without a message is kept as such`() {
    val run = queued()

    store.finish(run.id, RunState.FAILED, t0, FailureInfo("X", null, "t"))

    assertNull(store.find(run.id, Visibility.All)!!.failure!!.message)
  }

  @Test
  fun `finishing or advancing an unknown run changes nothing`() {
    assertFalse(store.finish(UUID.randomUUID(), RunState.FAILED, t0))
    assertFalse(store.advance(UUID.randomUUID(), RunState.RUNNING, t0))
  }

  // ---- restart ----

  @Test
  fun `a restart interrupts every run that has not ended and nothing else`() {
    val hash = pipelines.save("v1", "nightly")
    val id = definitionId(hash, "nightly")
    val waiting = store.insert(newRun(id))
    val running = store.insert(newRun(id)).also { store.advance(it.id, RunState.RUNNING, t0) }
    val unfinished =
        store.insert(newRun(id)).also { store.advance(it.id, RunState.TIMED_OUT_UNFINISHED, t0) }
    val done = store.insert(newRun(id)).also { store.finish(it.id, RunState.SUCCEEDED, t0) }
    val at = t0.plusSeconds(100)

    val interrupted = store.interruptUnfinished(at)

    assertEquals(
        setOf(waiting.id, running.id, unfinished.id),
        interrupted.map { it.id }.toSet(),
    )
    assertEquals(setOf("nightly"), interrupted.map { it.pipelineName }.toSet())
    for (r in listOf(waiting, running, unfinished)) {
      val stored = store.find(r.id, Visibility.All)!!
      assertEquals(RunState.INTERRUPTED, stored.state)
      assertEquals(at, stored.finishedAt)
    }
    assertEquals(RunState.SUCCEEDED, store.find(done.id, Visibility.All)!!.state)
    assertEquals(emptyList(), store.interruptUnfinished(at))
  }

  // ---- deletion is refused while runs refer to a definition ----

  @Test
  fun `an artifact that a run refers to cannot be deleted`() {
    val hash = pipelines.save("v1", "nightly")
    val run = store.insert(newRun(definitionId(hash, "nightly")))

    assertEquals(DeleteResult.InUse, artifacts.delete(hash, "alice"))

    assertNotNull(artifacts.find(hash, "alice"))
    assertNotNull(store.find(run.id, Visibility.All))
  }

  @Test
  fun `a definition that a run refers to cannot be deleted directly either`() {
    val hash = pipelines.save("v1", "nightly")
    store.insert(newRun(definitionId(hash, "nightly")))

    val e =
        assertFailsWith<SQLException> {
          dataSource.connection.use {
            it.createStatement().use { s -> s.execute("DELETE FROM pipeline_definition") }
          }
        }

    assertEquals("23503", e.sqlState)
  }

  @Test
  fun `an artifact whose runs are gone can be deleted again`() {
    val hash = pipelines.save("v1", "nightly")
    val run = store.insert(newRun(definitionId(hash, "nightly")))
    dataSource.connection.use {
      it.prepareStatement("DELETE FROM run WHERE id = ?").use { s ->
        s.setObject(1, run.id)
        s.executeUpdate()
      }
    }

    assertEquals(DeleteResult.Deleted, artifacts.delete(hash, "alice"))
  }

  @Test
  fun `a run is refused a state outside the lifecycle`() {
    val run = queued()

    val e =
        assertFailsWith<SQLException> {
          dataSource.connection.use {
            it.prepareStatement("UPDATE run SET state = 'PAUSED' WHERE id = ?").use { s ->
              s.setObject(1, run.id)
              s.executeUpdate()
            }
          }
        }

    assertEquals("23514", e.sqlState)
  }

  // ---- log ----

  @Test
  fun `log lines are numbered from one in the order they were appended`() {
    val run = queued()

    store.open(run.id).use {
      it.append(t0, LogStream.STDOUT, "first")
      it.append(t0.plusSeconds(1), LogStream.STDERR, "second")
      it.append(t0.plusSeconds(2), LogStream.STDOUT, "")
    }

    assertEquals(
        listOf(
            LogEntry(1, t0, LogStream.STDOUT, "first"),
            LogEntry(2, t0.plusSeconds(1), LogStream.STDERR, "second"),
            LogEntry(3, t0.plusSeconds(2), LogStream.STDOUT, ""),
        ),
        store.read(run.id, 0, 100),
    )
  }

  @Test
  fun `reading continues after a sequence number and honours the limit`() {
    val run = queued()
    store.open(run.id).use { a -> repeat(5) { a.append(t0, LogStream.STDOUT, "line-${it + 1}") } }

    assertEquals(listOf(3L, 4L), store.read(run.id, 2, 2).map { it.seq })
    assertEquals(listOf(5L), store.read(run.id, 4, 100).map { it.seq })
    assertEquals(emptyList(), store.read(run.id, 5, 100))
  }

  @Test
  fun `each run has its own log`() {
    val one = queued(pipelines.save("v1", "one"), "one")
    val two = queued(pipelines.save("v2", "two"), "two")

    store.open(one.id).use { it.append(t0, LogStream.STDOUT, "from one") }
    store.open(two.id).use { it.append(t0, LogStream.STDOUT, "from two") }

    assertEquals(listOf("from one"), store.read(one.id, 0, 10).map { it.line })
    assertEquals(listOf("from two"), store.read(two.id, 0, 10).map { it.line })
    assertEquals(listOf(1L), store.read(two.id, 0, 10).map { it.seq })
  }

  @Test
  fun `a log can be appended to again and numbering carries on`() {
    val run = queued()
    store.open(run.id).use { it.append(t0, LogStream.STDOUT, "a") }

    store.open(run.id).use { it.append(t0, LogStream.STDOUT, "b") }

    assertEquals(listOf(1L, 2L), store.read(run.id, 0, 10).map { it.seq })
  }

  @Test
  fun `a line with a NUL character is kept without it breaking the log`() {
    val run = queued()

    store.open(run.id).use {
      it.append(t0, LogStream.STDOUT, "bad\u0000byte")
      it.append(t0, LogStream.STDOUT, "next")
    }

    val lines = store.read(run.id, 0, 10).map { it.line }
    assertEquals(2, lines.size)
    assertFalse(lines[0].contains('\u0000'))
    assertTrue(lines[0].startsWith("bad") && lines[0].endsWith("byte"))
    assertEquals("next", lines[1])
  }

  @Test
  fun `entries written by one appender are visible to readers at once`() {
    val run = queued()
    store.open(run.id).use {
      it.append(t0, LogStream.STDOUT, "live")

      assertEquals(listOf("live"), store.read(run.id, 0, 10).map { e -> e.line })
    }
  }
}
