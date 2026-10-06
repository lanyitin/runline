package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.engine.artifact.ArtifactRecord
import dev.lawlan.runline.engine.artifact.DefinitionRecord
import kotlinx.serialization.Serializable

/**
 * A warning shown with an upload verdict. It does not change the verdict: declaring resources never
 * influences safe or unsafe (ADR-007).
 */
@Serializable data class WarningDoc(val kind: String, val resource: String, val message: String)

/** Warns about pipelines that declare shared resources that are missing or disabled. */
class ResourceWarnings(private val availability: ResourceAvailability) {
  /** A function giving the warnings of any definition of [artifacts], from one lookup. */
  fun lookup(artifacts: List<ArtifactRecord>): (DefinitionRecord) -> List<WarningDoc> {
    val names = artifacts.flatMap { a -> a.definitions.flatMap { it.metadata.resources } }
    val inspection = availability.inspect(names)
    return { definition ->
      inspection.problemsFor(definition.metadata.resources).map { it.toWarning() }
    }
  }

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
      }
}
