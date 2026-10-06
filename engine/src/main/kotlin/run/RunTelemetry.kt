package dev.lawlan.runline.engine.run

import dev.lawlan.runline.analyzer.Verdict
import dev.lawlan.runline.runner.LoaderStats
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Concurrency figures of the scheduler at one moment. */
data class SchedulerStats(val queued: Int, val waiting: Int, val active: Int)

/**
 * Trace and metrics of runs (07-nfr-risks). Each run has one trace: a root span from acceptance to
 * the end, carrying the pipeline and what caused the run, with a child span for each phase the run
 * went through. Metrics cover runs in progress, queued and waiting, runs that outlived their
 * timeout, class loaders created and reclaimed, and the share of unsafe runs.
 */
class RunTelemetry(openTelemetry: OpenTelemetry) {
  private val tracer = openTelemetry.getTracer("runline.run")
  private val meter = openTelemetry.getMeter("runline.run")
  private val created = meter.counterBuilder("runline.runs.created").build()
  private val refused = meter.counterBuilder("runline.runs.refused").build()
  private val ended = meter.counterBuilder("runline.runs.ended").build()
  private val residual = meter.counterBuilder("runline.runs.residual.threads").build()
  private val unfinished = meter.upDownCounterBuilder("runline.runs.timed_out_unfinished").build()

  private class Trace(val root: Span, var phase: Span, var state: RunState)

  private val traces = ConcurrentHashMap<UUID, Trace>()

  /** A run was accepted: starts its trace. */
  fun accepted(run: RunRecord, verdict: Verdict) {
    val root =
        tracer
            .spanBuilder("runline.run")
            .setNoParent()
            .setAttribute(RUN_ID, run.id.toString())
            .setAttribute(PIPELINE, run.pipelineName)
            .setAttribute(SOURCE_KIND, run.source.kind)
            .setAttribute(SOURCE_NAME, run.source.name)
            .setAttribute(VERDICT, verdict.name)
            .startSpan()
    traces[run.id] = Trace(root, phase(root, RunState.QUEUED), RunState.QUEUED)
    created.add(1, Attributes.of(SOURCE, run.source.kind, VERDICT_LABEL, verdict.name))
  }

  /** A run was refused before it existed. */
  fun refused(reason: String, source: RunSource) {
    refused.add(1, Attributes.of(REASON, reason))
  }

  /** The run entered the non terminal [state]; a state that is not forward is ignored. */
  fun entered(runId: UUID, state: RunState) {
    val trace = traces[runId] ?: return
    synchronized(trace) {
      if (!trace.state.canAdvanceTo(state)) return
      trace.phase.end()
      trace.phase = phase(trace.root, state)
      trace.state = state
      if (state == RunState.TIMED_OUT_UNFINISHED) unfinished.add(1)
    }
  }

  /** The run ended in [state]; closes its trace. */
  fun ended(runId: UUID, state: RunState, residualThreads: List<String>) {
    val trace = traces.remove(runId) ?: return
    synchronized(trace) {
      if (trace.state == RunState.TIMED_OUT_UNFINISHED) unfinished.add(-1)
      trace.phase.end()
      trace.root.setAttribute(STATE, state.name)
      if (state in ERROR_STATES) trace.root.setStatus(StatusCode.ERROR, state.name)
      trace.root.end()
    }
    ended.add(1, Attributes.of(STATE_LABEL, state.name))
    if (residualThreads.isNotEmpty()) residual.add(residualThreads.size.toLong())
  }

  fun observeScheduler(stats: () -> SchedulerStats) {
    meter.gaugeBuilder("runline.runs.queued").ofLongs().buildWithCallback {
      it.record(stats().queued.toLong())
    }
    meter.gaugeBuilder("runline.runs.waiting").ofLongs().buildWithCallback {
      it.record(stats().waiting.toLong())
    }
    meter.gaugeBuilder("runline.runs.active").ofLongs().buildWithCallback {
      it.record(stats().active.toLong())
    }
  }

  fun observeLoaders(stats: () -> LoaderStats) {
    meter.counterBuilder("runline.runner.classloaders.created").buildWithCallback {
      it.record(stats().created)
    }
    meter.counterBuilder("runline.runner.classloaders.reclaimed").buildWithCallback {
      it.record(stats().reclaimed)
    }
  }

  private fun phase(root: Span, state: RunState): Span =
      tracer
          .spanBuilder("runline.run.${state.name.lowercase()}")
          .setParent(Context.current().with(root))
          .startSpan()

  private companion object {
    val RUN_ID = AttributeKey.stringKey("runline.run.id")
    val PIPELINE = AttributeKey.stringKey("runline.pipeline")
    val SOURCE_KIND = AttributeKey.stringKey("runline.run.source.kind")
    val SOURCE_NAME = AttributeKey.stringKey("runline.run.source.name")
    val VERDICT = AttributeKey.stringKey("runline.run.verdict")
    val STATE = AttributeKey.stringKey("runline.run.state")
    val SOURCE = AttributeKey.stringKey("source")
    val VERDICT_LABEL = AttributeKey.stringKey("verdict")
    val STATE_LABEL = AttributeKey.stringKey("state")
    val REASON = AttributeKey.stringKey("reason")
    val ERROR_STATES = setOf(RunState.FAILED, RunState.TIMED_OUT, RunState.INTERRUPTED)
  }
}
