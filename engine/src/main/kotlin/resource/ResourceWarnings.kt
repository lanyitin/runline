package dev.lawlan.runline.engine.resource

import dev.lawlan.runline.engine.artifact.AccessLimitDoc
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
 * type than declared, about declared types that do not exist, and about a `network` that names the
 * host of a resource, which a pipeline should reach through the resource (ADR-019).
 */
class ResourceWarnings(
    private val availability: ResourceAvailability,
    private val store: ResourceStore,
    private val behaviors: ResourceBehaviors,
) {
  /** A function giving the warnings of any definition of [artifacts], from one lookup. */
  fun lookup(artifacts: List<ArtifactRecord>): (DefinitionRecord) -> List<WarningDoc> {
    // Whose a definition is: by identity, since two uploaders' definitions can be equal in content.
    val owners = java.util.IdentityHashMap<DefinitionRecord, PipelineKey>()
    artifacts.forEach { a ->
      a.definitions.forEach { owners[it] = PipelineKey(a.uploadedBy, it.name) }
    }
    val roles =
        owners.entries.groupBy({ it.value }, { it.key.metadata.resources }).mapValues {
            (_, declared) ->
          declared.flatten().distinct()
        }
    val inspection = availability.inspect(roles)
    val hosts = resourceHosts()
    return { definition ->
      val metadata = definition.metadata
      val types = metadata.resourceTypes
      val outsideTheSet = types.filterValues { ResourceType.fromWireName(it) == null }
      // A type outside the closed set has a warning of its own; it is not also a mismatch.
      inspection
          .problemsFor(owners.getValue(definition), metadata.resources, types)
          .filterNot { it.kind == ResourceProblemKind.TYPE_MISMATCH && it.role in outsideTheSet }
          .map { it.toWarning() } +
          outsideTheSet.map { (name, type) -> unknownType(name, type) } +
          networkHostsOfResources(metadata.network, hosts)
    }
  }

  /** The host of each resource that has one (`openai-compatible`, `jdbc-pool`), in lower case. */
  private fun resourceHosts(): List<Pair<String, String>> =
      store.list().mapNotNull { resource ->
        behaviors.of(resource.type)?.hostOf(resource)?.lowercase()?.let { resource.name to it }
      }

  private fun networkHostsOfResources(
      network: AccessLimitDoc,
      hosts: List<Pair<String, String>>,
  ): List<WarningDoc> {
    if (network.unrestricted) return emptyList()
    val declared = network.allow.map { it.lowercase() }.toSet()
    return hosts
        .filter { (_, host) -> host in declared }
        .map { (resource, host) ->
          WarningDoc(
              "network_host_has_resource",
              resource,
              "network 宣告的主機「$host」是共享資源「$resource」的主機；請改經由這個資源存取，不需要在 network 宣告它。",
          )
        }
  }

  private fun unknownType(name: String, type: String) =
      WarningDoc(
          "resource_type_unknown",
          name,
          "共享資源「$name」宣告的型別「$type」不在 counter、file、jdbc-pool、openai-compatible、trace-export 之內；" +
              "建立 run 時會視為型別不符。",
      )

  /**
   * The warning for a role that cannot be used. Its [WarningDoc.resource] is the resource the role
   * leads to; the message says the role when that is a resource of another name (ADR-025).
   */
  private fun ResourceProblem.toWarning(): WarningDoc {
    val of = if (resource == role) "共享資源「$resource」" else "資源角色「$role」所綁定的共享資源「$resource」"
    return when (kind) {
      ResourceProblemKind.UNKNOWN,
      ResourceProblemKind.UNBOUND ->
          WarningDoc(
              "resource_unknown",
              resource,
              "宣告的$of" + "尚未定義；在管理員定義它之前，這個 pipeline 無法建立 run。",
          )
      ResourceProblemKind.DISABLED ->
          WarningDoc(
              "resource_disabled",
              resource,
              "宣告的$of" + "已停用；在管理員啟用它之前，這個 pipeline 無法建立 run。",
          )
      ResourceProblemKind.TYPE_MISMATCH ->
          WarningDoc(
              "resource_type_mismatch",
              resource,
              "${of}的型別與 pipeline 宣告的不符；在兩者一致之前，這個 pipeline 無法建立 run。",
          )
    }
  }
}
