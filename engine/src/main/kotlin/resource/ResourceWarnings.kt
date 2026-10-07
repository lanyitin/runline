package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.engine.artifact.ArtifactRecord
import dev.lawlan.runline.engine.artifact.DefinitionRecord
import kotlinx.serialization.Serializable

/**
 * A warning shown with an upload verdict. It does not change the verdict: declaring resources never
 * influences safe or unsafe (ADR-007).
 */
@Serializable data class WarningDoc(val kind: String, val resource: String, val message: String)

/**
 * Warns about pipelines that declare shared resources that are missing or disabled, or of another
 * type than declared, and about declared types that do not exist.
 */
class ResourceWarnings(private val availability: ResourceAvailability) {
  /** A function giving the warnings of any definition of [artifacts], from one lookup. */
  fun lookup(artifacts: List<ArtifactRecord>): (DefinitionRecord) -> List<WarningDoc> {
    val names = artifacts.flatMap { a -> a.definitions.flatMap { it.metadata.resources } }
    val inspection = availability.inspect(names)
    return { definition ->
      val metadata = definition.metadata
      val types = metadata.resourceTypes
      val outsideTheSet = types.filterValues { ResourceType.fromWireName(it) == null }
      // A type outside the closed set has a warning of its own; it is not also a mismatch.
      inspection
          .problemsFor(metadata.resources, types)
          .filterNot { it.kind == ResourceProblemKind.TYPE_MISMATCH && it.name in outsideTheSet }
          .map { it.toWarning() } + outsideTheSet.map { (name, type) -> unknownType(name, type) }
    }
  }

  private fun unknownType(name: String, type: String) =
      WarningDoc(
          "resource_type_unknown",
          name,
          "共享資源「$name」宣告的型別「$type」不在 counter、file、jdbc-pool、openai-compatible 之內；" +
              "建立 run 時會視為型別不符。",
      )

  private fun ResourceProblem.toWarning() =
      when (kind) {
        ResourceProblemKind.UNKNOWN ->
            WarningDoc(
                "resource_unknown",
                name,
                "宣告的共享資源「$name」尚未定義；在管理員定義它之前，這個 pipeline 無法建立 run。",
            )
        ResourceProblemKind.DISABLED ->
            WarningDoc(
                "resource_disabled",
                name,
                "宣告的共享資源「$name」已停用；在管理員啟用它之前，這個 pipeline 無法建立 run。",
            )
        ResourceProblemKind.TYPE_MISMATCH ->
            WarningDoc(
                "resource_type_mismatch",
                name,
                "共享資源「$name」的型別與 pipeline 宣告的不符；在兩者一致之前，這個 pipeline 無法建立 run。",
            )
      }
}
