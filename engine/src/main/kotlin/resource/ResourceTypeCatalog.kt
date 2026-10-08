package dev.lawlan.runline.engine.resource

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * What the Engine tells of its closed set of resource types (WI-55, ADR-021): each type, in the
 * Engine's order, with what its behavior says of itself. The behaviors are the ones that check
 * settings, so what is told is what is accepted. Built once: it does not change while the Engine
 * runs.
 */
class ResourceTypeCatalog(behaviors: ResourceBehaviors) {
  val description: JsonObject = buildJsonObject {
    putJsonArray("types") {
      for (type in ResourceType.entries) {
        addJsonObject {
          put("type", type.wireName)
          behaviors.of(type)?.description()?.forEach { (name, value) -> put(name, value) }
        }
      }
    }
  }
}
