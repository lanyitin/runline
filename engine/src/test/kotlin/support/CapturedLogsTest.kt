package dev.lawlan.runline.engine.support

import ch.qos.logback.classic.Level
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory

class CapturedLogsTest {
  private val writers = 8
  private val eventsPerWriter = 5_000
  private val prefix = "captured-logs-stress"

  /** Many threads write while one thread keeps reading both views; the reads must never fail. */
  @Test
  fun `reading while many threads write neither fails nor loses an event`() {
    CapturedLogs().use { logs ->
      val pool = Executors.newFixedThreadPool(writers + 1)
      try {
        val start = CountDownLatch(1)
        val writing = AtomicBoolean(true)
        val reads =
            pool.submit<Throwable?> {
              start.await()
              try {
                do {
                  val stillWriting = writing.get()
                  logs.lines.count { it.contains(prefix) }
                  logs.at(Level.INFO).count { it.startsWith(prefix) }
                } while (stillWriting)
                null
              } catch (e: Throwable) {
                e
              }
            }
        val writes =
            (0 until writers).map { w ->
              pool.submit {
                val log = LoggerFactory.getLogger("$prefix-$w")
                start.await()
                repeat(eventsPerWriter) { n -> log.info("$prefix $w $n") }
              }
            }
        start.countDown()
        writes.forEach { it.get(60, TimeUnit.SECONDS) }
        writing.set(false)

        assertNull(reads.get(60, TimeUnit.SECONDS), "reading while writing threw")
        val seen = logs.at(Level.INFO).filter { it.startsWith(prefix) }
        assertEquals(writers * eventsPerWriter, seen.size)
        assertEquals(seen.size, seen.toSet().size, "an event was recorded twice")
        assertTrue(logs.lines.count { it.contains(prefix) } == seen.size)
      } finally {
        pool.shutdownNow()
      }
    }
  }
}
