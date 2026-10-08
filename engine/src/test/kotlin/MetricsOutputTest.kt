package dev.lawlan.runline.engine

import dev.lawlan.runline.engine.support.CapturedLogs
import dev.lawlan.runline.engine.support.awaitCondition
import dev.lawlan.runline.engine.support.configureEngine
import dev.lawlan.runline.engine.support.migratedDatabase
import io.ktor.server.testing.*
import kotlin.test.*

/**
 * Metrics leave the Engine only through OpenTelemetry; the log carries events and nothing written
 * on a schedule (07 "可觀測性", WI-61).
 */
class MetricsOutputTest {
  @Test
  fun `an Engine that has nothing happening writes nothing to the log`() {
    CapturedLogs().use { logs ->
      testApplication {
        configureEngine()
        startApplication()
        // What starting writes is done with once the application has started.
        val started = logs.lines.size
        // Longer than any period a reporter writing on a schedule has been given.
        Thread.sleep(IDLE_MILLIS)

        // The test host announces its connectors when it gets to it; those lines are not the
        // Engine's.
        assertEquals(emptyList(), logs.lines.drop(started).filterNot { it.startsWith(TEST_HOST) })
      }
    }
  }

  @Test
  fun `an Engine started and stopped many times leaves no background threads behind`() =
      withOtlpExport {
        val database = migratedDatabase()
        fun startAndStop() = testApplication {
          configureEngine(database)
          startApplication()
        }
        // The first start brings up what the process shares (the client's and the framework's own
        // pools); those are there once, not once per Engine.
        startAndStop()
        val before = liveThreads()

        repeat(CYCLES) { startAndStop() }

        awaitCondition(
            "the threads of $CYCLES stopped Engines have ended",
            diagnostics = { (liveThreads() - before).joinToString("\n") { it.name } },
        ) {
          (liveThreads() - before).isEmpty()
        }
      }

  /**
   * Runs [block] with traces and logs exported over OTLP, as a deployment does, so that the
   * exporters' threads are among those that have to end. Nothing listens at the endpoint: an Engine
   * that is only started and stopped has nothing to send.
   */
  private fun withOtlpExport(block: () -> Unit) {
    val settings =
        mapOf(
            "otel.traces.exporter" to "otlp",
            "otel.logs.exporter" to "otlp",
            "otel.exporter.otlp.endpoint" to "http://127.0.0.1:${unusedPort()}",
        )
    val previous = settings.keys.associateWith { System.getProperty(it) }
    settings.forEach { (key, value) -> System.setProperty(key, value) }
    try {
      block()
    } finally {
      previous.forEach { (key, value) ->
        if (value == null) System.clearProperty(key) else System.setProperty(key, value)
      }
    }
  }

  private fun unusedPort(): Int = java.net.ServerSocket(0).use { it.localPort }

  /**
   * The live threads, apart from the pools every coroutine and parallel stream of the process share
   * (kotlinx.coroutines' default scheduler, the JDK's common pool): they are sized by the machine,
   * not by how many Engines have run, and start or retire a worker whenever their load changes.
   */
  private fun liveThreads(): Set<Thread> =
      Thread.getAllStackTraces()
          .keys
          .filter { it.isAlive && SHARED_POOLS.none { pool -> it.name.startsWith(pool) } }
          .toSet()

  private companion object {
    const val IDLE_MILLIS = 11_000L
    const val TEST_HOST = "INFO io.ktor.test "
    const val CYCLES = 20
    val SHARED_POOLS = listOf("DefaultDispatcher-worker-", "ForkJoinPool.commonPool-worker-")
  }
}
