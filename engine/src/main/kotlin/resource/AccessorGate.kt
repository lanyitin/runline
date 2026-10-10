package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.accessors.BoundResources
import dev.lawlan.runline.accessors.Invalidation
import dev.lawlan.runline.accessors.ResourceBinding
import dev.lawlan.runline.engine.run.FailureInfo
import dev.lawlan.runline.engine.run.GateDecision
import dev.lawlan.runline.engine.run.PendingRun
import dev.lawlan.runline.engine.run.ResourceGate
import dev.lawlan.runline.engine.run.RunTelemetry
import dev.lawlan.runline.runner.ResourceHost
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The gate of the Engine: the [ResourceCoordinator] decides who holds what, and on top of that a
 * run that holds typed resources is given accessors for them (ADR-019). The accessors are prepared
 * as soon as the capacity is granted, from the definitions as they are at that moment, so a change
 * made later does not reach a run that already holds the resource. A resource whose entity cannot
 * be used refuses the run, and the capacity it was granted goes back.
 */
class AccessorGate(
    private val coordinator: ResourceCoordinator,
    private val behaviors: ResourceBehaviors,
    private val runTelemetry: RunTelemetry,
    private val telemetry: ResourceTelemetry,
) : ResourceGate {
  private class Prepared(
      val host: BoundResources,
      val observer: EngineResourceObserver,
      /** The roles that got each resource, by resource name. */
      val rolesOf: Map<String, List<String>>,
  )

  private val prepared = ConcurrentHashMap<UUID, Prepared>()

  override fun tryAcquire(run: PendingRun): GateDecision {
    val decision = coordinator.tryAcquire(run)
    if (decision != GateDecision.GRANTED || run.roleTypes.isEmpty()) return decision
    if (prepared.containsKey(run.id)) return decision
    return try {
      prepared[run.id] = prepare(run)
      decision
    } catch (e: ResourceUnavailable) {
      coordinator
          .granted(run.id)[e.resource]
          ?.takeIf { it.type == ResourceType.FILE }
          ?.let { telemetry.pathCheckFailed(ResourceLabel(it.name, it.type.wireName)) }
      GateDecision.Refused(
          FailureInfo(
              ResourceFailures.UNAVAILABLE,
              "資源不可用：${e.resource}；run 未開始。",
              "",
          )
      )
    }
  }

  init {
    coordinator.beforeForcedRelease { runId, resource ->
      prepared[runId]?.let { ready ->
        // Every role that got the resource: the pipeline knows it by its roles only.
        ready.rolesOf[resource].orEmpty().forEach {
          ready.host.invalidate(it, Invalidation.FORCE_RELEASED)
        }
      }
    }
  }

  override fun release(runId: UUID) {
    // The accessors stop first: nothing the run still does may reach an entity another run gets.
    prepared.remove(runId)?.host?.invalidateAll(Invalidation.RUN_ENDED)
    coordinator.release(runId)
  }

  override fun attach(wake: () -> Unit) = coordinator.attach(wake)

  override fun accessors(runId: UUID, log: (String) -> Unit): ResourceHost? {
    val held = coordinator.granted(runId)
    val roles = coordinator.grantedRoles(runId).entries.groupBy({ it.value.name }, { it.key })
    held.forEach { (name, resource) ->
      val by = roles[name].orEmpty().filter { it != name }
      log(
          "[資源] 已取得 $name（${resource.type.wireName}）" +
              if (by.isEmpty()) "" else "，供角色 ${by.joinToString("、")} 使用"
      )
    }
    val ready = prepared[runId] ?: return null
    ready.observer.runLog = log
    return ready.host
  }

  private fun prepare(run: PendingRun): Prepared {
    val granted = coordinator.grantedRoles(run.id)
    // One binding for each resource, shared by every role that got it: the run's share of the
    // resource (its connections, its requests) is the run's, not each role's (ADR-025).
    val byResource = LinkedHashMap<String, ResourceBinding>()
    val bindings = LinkedHashMap<String, ResourceBinding>()
    val rolesOf = LinkedHashMap<String, MutableList<String>>()
    try {
      for (role in run.roleTypes.keys) {
        val resource = granted[role] ?: continue
        val binding =
            byResource[resource.name]
                ?: behaviors.of(resource.type)?.bind(resource)?.also {
                  byResource[resource.name] = it
                }
                ?: continue
        bindings[role] = binding
        rolesOf.getOrPut(resource.name) { ArrayList() } += role
      }
    } catch (e: Throwable) {
      // What was bound before the refusal is let go of: a binding that is never closed holds its
      // resource's pool for good (a generation of connections that can then never be closed).
      byResource.values.forEach { runCatching { it.close() } }
      throw e
    }
    val resourceOf = granted.mapValues { it.value.name }
    val observer =
        EngineResourceObserver(run.id, run.pipelineName, runTelemetry, telemetry, resourceOf)
    return Prepared(BoundResources(bindings, observer), observer, rolesOf)
  }
}
