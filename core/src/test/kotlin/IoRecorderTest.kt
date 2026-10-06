package dev.lawlan.runline.core

import java.util.concurrent.CountDownLatch
import kotlin.test.*

class IoRecorderTest {

  @Suppress("UNCHECKED_CAST")
  private fun IoRecorder.events(): List<Map<String, Any?>> =
      snapshot()["events"] as List<Map<String, Any?>>

  @Suppress("UNCHECKED_CAST")
  private fun IoRecorder.summary(): List<Map<String, Any?>> =
      snapshot()["summary"] as List<Map<String, Any?>>

  @Test
  fun `each event carries category, target, access and its order`() {
    val recorder = IoRecorder(maxEvents = 100)

    recorder.record(IoCategory.FILE, "a/b.txt", IoAccess.READ, scope = FileScope.PIPELINE_SHARED)
    recorder.record(IoCategory.NETWORK, "Example.com", IoAccess.WRITE, port = 8080)
    recorder.record(IoCategory.PROCESS, "git", IoAccess.WRITE)

    val events = recorder.events()
    assertEquals(listOf(1L, 2L, 3L), events.map { it["sequence"] })
    assertEquals(listOf("FILE", "NETWORK", "PROCESS"), events.map { it["category"] })
    assertEquals(listOf("a/b.txt", "Example.com", "git"), events.map { it["target"] })
    assertEquals(listOf("READ", "WRITE", "WRITE"), events.map { it["access"] })
    assertEquals("PIPELINE_SHARED", events[0]["scope"])
    assertEquals(8080, events[1]["port"])
    assertEquals(false, events[0]["rejected"])
  }

  @Test
  fun `keeps at most the limit as individual events and folds all of them into the summary`() {
    val recorder = IoRecorder(maxEvents = 5)

    repeat(1000) {
      recorder.record(IoCategory.FILE, "f.txt", IoAccess.READ, scope = FileScope.RUN_PRIVATE)
    }

    val snapshot = recorder.snapshot()
    assertEquals(1000L, snapshot["total"])
    assertEquals(5, recorder.events().size)
    assertEquals(listOf(1L, 2L, 3L, 4L, 5L), recorder.events().map { it["sequence"] })
    val summary = recorder.summary().single()
    assertEquals(1000L, summary["count"])
    assertEquals(1L, summary["firstSequence"])
    assertEquals(1000L, summary["lastSequence"])
  }

  @Test
  fun `many different files collapse into one summary entry per scope and access`() {
    val recorder = IoRecorder(maxEvents = 10)

    repeat(5000) {
      recorder.record(
          IoCategory.FILE,
          "dir/file-$it",
          IoAccess.WRITE,
          scope = FileScope.RUN_PRIVATE,
      )
    }

    val summary = recorder.summary()
    assertEquals(1, summary.size)
    assertEquals(5000L, summary.single()["count"])
    assertEquals("RUN_PRIVATE", summary.single()["scope"])
    assertEquals("WRITE", summary.single()["access"])
    assertEquals("", summary.single()["target"], "the path is not kept in the summary")
  }

  @Test
  fun `many different hosts and commands are all kept in the summary, hosts without case or port`() {
    val recorder = IoRecorder(maxEvents = 3)

    repeat(500) {
      recorder.record(IoCategory.NETWORK, "Host$it.Example", IoAccess.WRITE, port = it + 1)
    }
    repeat(500) { recorder.record(IoCategory.NETWORK, "HOST$it.example", IoAccess.WRITE, port = 9) }
    repeat(200) { recorder.record(IoCategory.PROCESS, "cmd$it", IoAccess.WRITE) }

    val summary = recorder.summary()
    val hosts = summary.filter { it["category"] == "NETWORK" }.map { it["target"] }.toSet()
    assertEquals((0 until 500).map { "host$it.example" }.toSet(), hosts)
    assertEquals(
        (0 until 200).map { "cmd$it" }.toSet(),
        summary.filter { it["category"] == "PROCESS" }.map { it["target"] }.toSet(),
    )
    assertEquals(1200L, recorder.snapshot()["total"])
    assertEquals(3, recorder.events().size)
  }

  @Test
  fun `rejected events are summarized apart from allowed ones`() {
    val recorder = IoRecorder(maxEvents = 100)

    recorder.record(IoCategory.FILE, "x", IoAccess.READ, scope = FileScope.RUN_PRIVATE)
    recorder.record(
        IoCategory.FILE,
        "../x",
        IoAccess.READ,
        scope = FileScope.RUN_PRIVATE,
        rejected = true,
    )

    assertEquals(setOf(false, true), recorder.summary().map { it["rejected"] }.toSet())
    assertEquals(listOf(false, true), recorder.events().map { it["rejected"] })
  }

  @Test
  fun `the snapshot holds only JDK types`() {
    val recorder = IoRecorder(maxEvents = 1)
    repeat(3) {
      recorder.record(IoCategory.FILE, "f", IoAccess.READ, scope = FileScope.PIPELINE_SHARED)
      recorder.record(IoCategory.NETWORK, "h", IoAccess.WRITE, port = 1)
    }

    fun check(value: Any?) {
      when (value) {
        null -> Unit
        is Map<*, *> ->
            value.forEach { (k, v) ->
              check(k)
              check(v)
            }
        is Iterable<*> -> value.forEach { check(it) }
        else -> assertNull(value.javaClass.classLoader, "${value.javaClass.name} is not a JDK type")
      }
    }
    check(recorder.snapshot())
  }

  @Test
  fun `concurrent recording loses nothing and numbers every event once`() {
    val recorder = IoRecorder(maxEvents = 50)
    val threads = 8
    val each = 500
    val start = CountDownLatch(1)
    val workers =
        (1..threads).map { t ->
          Thread {
            start.await()
            repeat(each) { recorder.record(IoCategory.PROCESS, "cmd$t", IoAccess.WRITE) }
          }
              .apply { start() }
        }
    start.countDown()
    workers.forEach {
      it.join(30_000)
      assertFalse(it.isAlive, "recording thread did not finish within 30 s")
    }

    assertEquals((threads * each).toLong(), recorder.snapshot()["total"])
    assertEquals(
        (threads * each).toLong(),
        recorder.summary().sumOf { it["count"] as Long },
    )
    val sequences = recorder.events().map { it["sequence"] as Long }
    assertEquals(sequences.sorted(), sequences, "stored in the order of their numbers")
    assertEquals(sequences.size, sequences.toSet().size)
  }

  @Test
  fun `a limit of zero keeps only the summary`() {
    val recorder = IoRecorder(maxEvents = 0)
    recorder.record(IoCategory.PROCESS, "echo", IoAccess.WRITE)

    assertTrue(recorder.events().isEmpty())
    assertEquals(1L, recorder.summary().single()["count"])
  }

  @Test
  fun `a negative limit is refused`() {
    assertFailsWith<IllegalArgumentException> { IoRecorder(maxEvents = -1) }
  }
}
