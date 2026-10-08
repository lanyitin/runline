package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.engine.artifact.Visibility
import dev.lawlan.runline.engine.auth.ApiIdentity
import dev.lawlan.runline.engine.auth.Role
import dev.lawlan.runline.engine.run.CancelResult
import dev.lawlan.runline.engine.run.RunState
import dev.lawlan.runline.engine.support.RunHarness
import dev.lawlan.runline.engine.support.RunHarness.Companion.usingTyped
import dev.lawlan.runline.engine.support.SlowRunEnd
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import java.nio.file.Files
import java.time.Duration
import java.util.UUID
import kotlin.test.*

/**
 * Whoever sees a run as ended sees it holding nothing (ADR-007 "Run 終止時", WI-59): by then its
 * resources are given back, a waiting run can have them, and its accessors refuse every call. Each
 * way a run can end is looked at with time put between the steps of the end ([SlowRunEnd]), so that
 * a wrong order is seen every time rather than under load only. Everything else is real:
 * PostgreSQL, the coordinator and scheduler, the Runner with a class loader per run, and `file`
 * resources on the real file system.
 */
class RunEndOrderingTest {
  private val harnesses = mutableListOf<RunHarness>()
  private val metrics = InMemoryMetricReader.create()
  private val root = ApiIdentity("root", Role.ADMIN)

  private fun harness(runTimeout: Duration? = null) =
      RunHarness(
              maxConcurrent = 3,
              runTimeout = runTimeout,
              resourceWaitTimeout = Duration.ofHours(1),
              openTelemetry =
                  OpenTelemetrySdk.builder()
                      .setMeterProvider(
                          SdkMeterProvider.builder().registerMetricReader(metrics).build()
                      )
                      .build(),
              gateAround = { SlowRunEnd(it, PAUSE, PAUSE) },
          )
          .also { harnesses += it }

  @AfterTest fun closeAll() = harnesses.forEach { it.close() }

  @Test
  fun `a run that succeeded is seen ended only once it holds nothing`() {
    val h = harness()
    seenEndedHoldingNothing(h, RunState.SUCCEEDED, ending = "") { release() }
  }

  @Test
  fun `a run that failed is seen ended only once it holds nothing`() {
    val h = harness()
    seenEndedHoldingNothing(
        h,
        RunState.FAILED,
        ending = """throw new RuntimeException("boom");""",
    ) {
      release()
    }
  }

  @Test
  fun `a run that was cancelled is seen ended only once it holds nothing`() {
    val h = harness()
    seenEndedHoldingNothing(h, RunState.CANCELLED, RunHarness.WAIT_FOR_STOP, hold = false) { run ->
      assertEquals(CancelResult.CancellationRequested, service.cancel(run, Visibility.All))
    }
  }

  @Test
  fun `a run that timed out is seen ended only once it holds nothing`() {
    val h = harness(runTimeout = Duration.ofSeconds(3))
    seenEndedHoldingNothing(h, RunState.TIMED_OUT, RunHarness.WAIT_FOR_STOP, hold = false) {}
  }

  @Test
  fun `a run that was forced to let go of one resource is seen ended only once it holds nothing`() {
    val h = harness()
    h.defineFile("other", "other.txt")
    seenEndedHoldingNothing(h, RunState.SUCCEEDED, ending = "", also = listOf("other")) { run ->
      assertIs<ForceReleaseResult.Released>(coordinator!!.forceRelease("other", run, root))
      release()
    }
  }

  /**
   * Starts a run that holds the `file` resource `log` (and [also]) and leaves behind a thread that
   * calls the accessor of `log` once it is told to; a second run waits for `log`. [end] makes the
   * first run end as [expected], after which it runs [ending] (with [hold], only once it is told
   * to). From the first moment the run is seen ended, through the API's store or the ended-runs
   * metric, it must hold nothing, its accessor must refuse, and the waiting run must get `log`.
   */
  private fun seenEndedHoldingNothing(
      h: RunHarness,
      expected: RunState,
      ending: String,
      hold: Boolean = true,
      also: List<String> = emptyList(),
      end: RunHarness.(UUID) -> Unit,
  ) {
    h.defineFile("log", "out.txt")
    val held = listOf("log") + also
    val body =
        """
        final FileAccessor log = context.getAccessors().file("log");
        final PipelineContext ctx = context;
        log.writeText("early");
        new ResourceAccessException("preload", ResourceFailure.FAILED, null);
        ctx.getFiles().exists(FileScope.PIPELINE_SHARED, "go");
        ctx.getFiles().writeText(FileScope.PIPELINE_SHARED, "warm", "x");
        Thread straggler = new Thread(() -> {
          try {
            while (!ctx.getFiles().exists(FileScope.PIPELINE_SHARED, "go")) Thread.sleep(2);
          } catch (InterruptedException e) { return; }
          String result = "ok";
          try { log.writeText("late"); }
          catch (ResourceAccessException e) { result = e.getFailure().name(); }
          catch (Throwable t) { result = t.toString(); }
          ctx.getFiles().writeText(FileScope.PIPELINE_SHARED, "late-outcome", result);
        });
        straggler.setDaemon(true);
        straggler.start();
        ctx.getFiles().writeText(FileScope.PIPELINE_SHARED, "started", "x");
        ${if (hold) WAIT_FOR_RELEASE else ""}
        $ending
        """
            .trimIndent()
    val first =
        h.upload("first", body, declaration = usingTyped(*held.map { it to "file" }.toTypedArray()))
    val next = h.upload("next", "", declaration = usingTyped("log" to "file"))
    val run = h.start(first, "first")
    h.awaitFile(h.shared("first", "started"))
    val waiting = h.start(next, "next")
    h.await(waiting, RunState.WAITING_FOR_RESOURCES)
    val endedBefore = endedRuns()

    h.end(run)

    val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
    while (!h.state(run).terminal && endedRuns() == endedBefore) {
      check(System.nanoTime() < deadline) { "the run never ended" }
      Thread.onSpinWait()
    }
    // The waiting run may hold `log` already; the run that ended must not hold anything.
    val stillHeld = held.filter { name ->
      h.coordinator!!.activity(name).holders.any { it.runId == run }
    }
    Files.writeString(h.shared("first", "go"), "x")

    assertEquals(emptyList(), stillHeld, "seen ended, still holding")
    h.awaitFile(h.shared("first", "late-outcome"))
    assertEquals("ENDED", Files.readString(h.shared("first", "late-outcome")))
    assertEquals("early", Files.readString(h.resourceRoot.resolve("out.txt")))
    assertEquals(expected, h.state(run))
    assertEquals(RunState.SUCCEEDED, h.awaitEnd(waiting).state)
  }

  /** Lets the first run go on past [WAIT_FOR_RELEASE]. */
  private fun RunHarness.release() {
    Files.writeString(shared("first", "release"), "x")
  }

  /** How many runs the ended-runs metric has counted, of any state. */
  private fun endedRuns(): Long =
      metrics
          .collectAllMetrics()
          .filter { it.name == "runline.runs.ended" }
          .flatMap { it.longSumData.points }
          .sumOf { it.value }

  private companion object {
    /** Long enough that a run seen ended in the middle of its end is seen so every time. */
    val PAUSE: Duration = Duration.ofMillis(400)

    const val WAIT_FOR_RELEASE =
        """
        try {
          while (!ctx.getFiles().exists(FileScope.PIPELINE_SHARED, "release")) Thread.sleep(10);
        } catch (InterruptedException e) { throw new RuntimeException(e); }
        """
  }
}
