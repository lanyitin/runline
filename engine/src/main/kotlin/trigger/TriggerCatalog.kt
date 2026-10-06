package dev.lawlan.runline.engine.trigger

import dev.lawlan.runline.engine.artifact.DefinitionStore
import dev.lawlan.runline.engine.run.ParameterCheck
import dev.lawlan.runline.engine.run.validateParameters

/** A trigger with the parameters its runs will get once the defaults of the version are added. */
data class TriggerView(val trigger: Trigger, val effectiveParameters: Map<String, String>)

/** What an administrator can look up about triggers and their firings. */
class TriggerCatalog(
    private val definitions: DefinitionStore,
    private val triggers: TriggerStore,
) {
  fun find(name: String): TriggerView? = triggers.find(name)?.let(::viewOf)

  fun list(): List<TriggerView> = triggers.list().map(::viewOf)

  /** The most recent firings of the trigger [name], newest first; null for no such trigger. */
  fun firings(name: String, limit: Int): List<Firing>? =
      triggers.find(name)?.let { triggers.firings(it.id, limit) }

  private fun viewOf(trigger: Trigger): TriggerView {
    val declared = definitions.find(trigger.contentHash, trigger.pipeline)?.metadata?.parameters
    val check = declared?.let { validateParameters(it, trigger.parameters) }
    // The binding was checked when it was made, and a version never changes.
    val effective = (check as? ParameterCheck.Valid)?.effective ?: trigger.parameters
    return TriggerView(trigger, effective)
  }
}
