package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.engine.auth.ApiIdentity
import dev.lawlan.runline.engine.run.FailureInfo
import dev.lawlan.runline.engine.run.GateDecision
import dev.lawlan.runline.engine.run.PendingRun
import dev.lawlan.runline.engine.run.ResourceGate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/** A run that holds a resource, since when. */
data class Holder(val runId: UUID, val pipelineName: String, val since: Instant)

/** A run waiting for resources: which, and since when. Listed in the order they will be served. */
data class Waiter(
    val runId: UUID,
    val pipelineName: String,
    val waitingFor: List<String>,
    val since: Instant,
)

/** Who holds one resource and who waits for it, at one moment. */
data class ResourceActivity(val holders: List<Holder>, val waiters: List<Waiter>)

sealed interface ForceReleaseResult {
  data class Released(val holder: Holder) : ForceReleaseResult

  /** The run does not hold that resource (it may have ended just before). */
  data object NotHeld : ForceReleaseResult
}

/** How an attempt to remove a resource ended. */
sealed interface RemovalOutcome {
  data object Removed : RemovalOutcome

  /** There is no such resource. */
  data object NotFound : RemovalOutcome

  /** Runs hold it or wait for it; nothing was changed. */
  data class InUse(val holders: Int, val waiters: Int) : RemovalOutcome
}

/**
 * Shared resources while runs use them (ADR-007). A run is granted everything it declares or
 * nothing: the decision looks at all its resources under one lock, so a run never holds some while
 * it waits for others. Waiting runs are served in the order they began to wait; a run that arrives
 * later does not take a resource an earlier waiter declared, even when it is free at the moment.
 *
 * Holders and waiters live only here, in memory (single Engine process); definitions (capacity,
 * enabled) are read from the store each time a run is looked at, so a change by an administrator
 * applies at the next look. A holder is never taken away except by an administrator or the end of
 * the run, however long it holds; only waiters time out. Everything that crosses between run and
 * Engine stays on the Engine's side of the class loader boundary: runs are known here only by id.
 */
class ResourceCoordinator(
    private val availability: ResourceAvailability,
    private val clock: Clock,
    private val waitTimeout: Duration,
    private val telemetry: ResourceTelemetry,
) : ResourceGate, AutoCloseable {
  private val log = LoggerFactory.getLogger(ResourceCoordinator::class.java)

  private class Hold(
      val runId: UUID,
      val pipeline: String,
      val since: Instant,
      /** The type each held resource had when it was acquired. */
      val types: Map<String, String>,
  ) {
    val resources = LinkedHashSet<String>()
  }

  private class Waiting(
      val runId: UUID,
      val pipeline: String,
      val names: List<String>,
      val since: Instant,
      val types: Map<String, String>,
  ) {
    var expired = false
    var timer: ScheduledFuture<*>? = null
  }

  private val lock = Any()
  private val holds = LinkedHashMap<UUID, Hold>()
  private val waiting = LinkedHashMap<UUID, Waiting>()
  /** Every resource that has been held or waited for, with its type then, for the gauges. */
  private val known = LinkedHashMap<String, String>()

  /** How many resources have been removed; changed only under [lock]. */
  @Volatile private var removals = 0L
  private val timers = Executors.newSingleThreadScheduledExecutor { task ->
    Thread.ofPlatform().name("resource-wait-timer").daemon(true).unstarted(task)
  }

  @Volatile private var wakeScheduler: () -> Unit = {}

  init {
    telemetry.observe(::counts)
  }

  override fun attach(wake: () -> Unit) {
    wakeScheduler = wake
  }

  /** Asks the scheduler to look at its waiting runs again (a definition changed). */
  fun wake() = wakeScheduler()

  override fun tryAcquire(run: PendingRun): GateDecision {
    val names = run.resources.distinct()
    if (names.isEmpty()) return GateDecision.GRANTED
    while (true) {
      // The definitions are read before the lock is taken. A resource removed in between must not
      // be granted on the strength of that reading, so a removal is counted and, when one
      // happened meanwhile, the definitions are read again.
      val removalsSeen = removals
      val inspection = availability.inspect(names, run.resourceTypes)
      synchronized(lock) {
        if (removals != removalsSeen) return@synchronized
        if (inspection.problems.isNotEmpty()) return refuse(run, inspection)
        if (holds[run.id]?.resources?.containsAll(names) == true) return GateDecision.GRANTED
        val grantable = names.all { name ->
          holders(name) < inspection.defined.getValue(name).capacity && !waiterAhead(name, run.id)
        }
        if (grantable) return grant(run, names, inspection.defined)
        val waiter = waiting[run.id]
        if (waiter == null) {
          register(run, names, inspection.defined)
        } else if (waiter.expired) {
          return timeOut(waiter)
        }
        return GateDecision.WAIT
      }
    }
  }

  override fun release(runId: UUID) {
    val changed =
        synchronized(lock) {
          val waited = removeWaiting(runId)?.also { cancelled(it) }
          val held = holds.remove(runId)?.also { ended(it) }
          waited != null || held != null
        }
    if (changed) wake()
  }

  fun forceRelease(resource: String, runId: UUID, by: ApiIdentity): ForceReleaseResult {
    val holder =
        synchronized(lock) {
          val hold = holds[runId]
          if (hold == null || !hold.resources.remove(resource)) return ForceReleaseResult.NotHeld
          if (hold.resources.isEmpty()) holds.remove(runId)
          telemetry.held(hold.label(resource), secondsSince(hold.since), forced = true)
          Holder(hold.runId, hold.pipeline, hold.since)
        }
    log.warn(
        "Resource {} released by force from run {} (pipeline {}), held since {}, by {}",
        resource,
        runId,
        holder.pipelineName,
        holder.since,
        by.name,
    )
    wake()
    return ForceReleaseResult.Released(holder)
  }

  /**
   * Removes [resource] by calling [remove] (which returns whether there was such a resource),
   * unless a run holds it or waits for it. This and the grant of resources decide under the same
   * lock, so a resource that is removed is never granted afterwards and one that is granted is not
   * removed.
   */
  fun removeWhenUnused(resource: String, remove: () -> Boolean): RemovalOutcome =
      synchronized(lock) {
        val holders = holders(resource)
        val waiters = waiting.values.count { resource in it.names }
        when {
          holders > 0 || waiters > 0 -> RemovalOutcome.InUse(holders, waiters)
          !remove() -> RemovalOutcome.NotFound
          else -> {
            known.remove(resource)
            removals++
            RemovalOutcome.Removed
          }
        }
      }

  fun activity(resource: String): ResourceActivity =
      synchronized(lock) {
        ResourceActivity(
            holds.values
                .filter { resource in it.resources }
                .map { Holder(it.runId, it.pipeline, it.since) },
            waiting.values
                .filter { resource in it.names }
                .map { Waiter(it.runId, it.pipeline, it.names, it.since) },
        )
      }

  override fun close() {
    timers.shutdownNow()
  }

  // ---- all of these run under [lock] ----

  private fun holders(resource: String) = holds.values.count { resource in it.resources }

  /**
   * Whether a run that began waiting before [runId] (or any, if it has not) declared [resource].
   */
  private fun waiterAhead(resource: String, runId: UUID): Boolean {
    for (waiter in waiting.values) {
      if (waiter.runId == runId) return false
      if (resource in waiter.names) return true
    }
    return false
  }

  private fun grant(
      run: PendingRun,
      names: List<String>,
      defined: Map<String, SharedResource>,
  ): GateDecision {
    val waited = removeWaiting(run.id)
    val now = clock.instant()
    val types = typesOf(names, defined)
    val hold = Hold(run.id, run.pipelineName, now, types).also { it.resources += names }
    holds[run.id] = hold
    known += types
    val seconds = waited?.let { secondsSince(it.since) } ?: 0.0
    names.forEach { telemetry.waited(hold.label(it), WaitOutcome.ACQUIRED, seconds) }
    log.info(
        "Run {} (pipeline {}) acquired {} after waiting {} s",
        run.id,
        run.pipelineName,
        names,
        "%.3f".format(seconds),
    )
    return GateDecision.GRANTED
  }

  private fun register(
      run: PendingRun,
      names: List<String>,
      defined: Map<String, SharedResource>,
  ) {
    val types = typesOf(names, defined)
    val waiter = Waiting(run.id, run.pipelineName, names, clock.instant(), types)
    waiting[run.id] = waiter
    known += types
    waiter.timer =
        timers.schedule(
            {
              synchronized(lock) { waiter.expired = true }
              wake()
            },
            waitTimeout.toMillis(),
            TimeUnit.MILLISECONDS,
        )
    log.info(
        "Run {} (pipeline {}) waits for {}; {} run(s) wait",
        run.id,
        run.pipelineName,
        names,
        waiting.size,
    )
  }

  private fun removeWaiting(runId: UUID): Waiting? =
      waiting.remove(runId)?.also { it.timer?.cancel(false) }

  private fun timeOut(waiter: Waiting): GateDecision {
    removeWaiting(waiter.runId)
    val seconds = secondsSince(waiter.since)
    waiter.names.forEach { telemetry.waited(waiter.label(it), WaitOutcome.TIMED_OUT, seconds) }
    log.warn(
        "Run {} (pipeline {}) gave up waiting for {} after {} s",
        waiter.runId,
        waiter.pipeline,
        waiter.names,
        "%.3f".format(seconds),
    )
    return GateDecision.Refused(
        FailureInfo(
            ResourceFailures.WAIT_TIMEOUT,
            "等待資源逾時：等待 ${waiter.names.joinToString("、")} 超過 ${describe(waitTimeout)} 仍未取得，run 未開始。",
            "",
        )
    )
  }

  private fun refuse(run: PendingRun, inspection: ResourceInspection): GateDecision {
    val problems = inspection.problems
    val waited = removeWaiting(run.id)
    val seconds = waited?.let { secondsSince(it.since) } ?: 0.0
    problems.forEach {
      val type = inspection.defined[it.name]?.type?.wireName ?: ResourceTelemetry.UNDEFINED_TYPE
      telemetry.waited(ResourceLabel(it.name, type), WaitOutcome.REFUSED, seconds)
    }
    val text = problems.joinToString("、") { "${it.name}（${it.kind.label}）" }
    log.warn("Run {} (pipeline {}) cannot get resources: {}", run.id, run.pipelineName, text)
    return GateDecision.Refused(
        FailureInfo(ResourceFailures.UNAVAILABLE, "無法取得資源：$text；run 未開始。", "")
    )
  }

  private fun cancelled(waiter: Waiting) {
    val seconds = secondsSince(waiter.since)
    waiter.names.forEach { telemetry.waited(waiter.label(it), WaitOutcome.CANCELLED, seconds) }
    log.info(
        "Run {} (pipeline {}) stopped waiting for {}",
        waiter.runId,
        waiter.pipeline,
        waiter.names,
    )
  }

  private fun ended(hold: Hold) {
    val seconds = secondsSince(hold.since)
    hold.resources.forEach { telemetry.held(hold.label(it), seconds, forced = false) }
    log.info(
        "Run {} (pipeline {}) released {} after holding {} s",
        hold.runId,
        hold.pipeline,
        hold.resources.toList(),
        "%.3f".format(seconds),
    )
  }

  private fun counts(): Map<ResourceLabel, ResourceCounts> =
      synchronized(lock) {
        known.entries.associate { (name, type) ->
          ResourceLabel(name, type) to
              ResourceCounts(holders(name), waiting.values.count { name in it.names })
        }
      }

  private fun typesOf(names: List<String>, defined: Map<String, SharedResource>) =
      names.associateWith {
        defined.getValue(it).type.wireName
      }

  private fun Hold.label(resource: String) = ResourceLabel(resource, types.getValue(resource))

  private fun Waiting.label(resource: String) = ResourceLabel(resource, types.getValue(resource))

  private fun secondsSince(instant: Instant) =
      Duration.between(instant, clock.instant()).toNanos() / 1_000_000_000.0

  private fun describe(duration: Duration) =
      if (duration.toMillis() < 1000) "${duration.toMillis()} 毫秒" else "${duration.toSeconds()} 秒"
}

/** The failure types a run gets when it cannot start because of its resources. */
object ResourceFailures {
  const val WAIT_TIMEOUT = "ResourceWaitTimeout"
  const val UNAVAILABLE = "ResourceUnavailable"
}
