package dev.lawlan.runline.engine.resource

/**
 * A pipeline definition that reaches a shared resource through one of its roles (ADR-025): the role
 * is bound to the resource, or has no binding and is named like it.
 */
data class DeclaringDefinition(
    val contentHash: String,
    /** Whose version of the content declares it (ADR-020). */
    val uploader: String,
    val pipeline: String,
    /** The role of the definition that leads to the resource. */
    val role: String,
    /** The type the definition expects of the role; null when it declares the name only. */
    val declaredType: String?,
    /** How many triggers are bound to this definition, and so go through its declaration. */
    val triggers: Int,
)

/**
 * Who reaches one resource: the definitions (one entry for each role of a definition that leads to
 * it), and with them the triggers that would be affected.
 */
data class ResourceDeclarations(val definitions: List<DeclaringDefinition>) {
  private val distinctDefinitions: List<DeclaringDefinition>
    get() = definitions.distinctBy { Triple(it.contentHash, it.uploader, it.pipeline) }

  /** How many definitions, however many of their roles lead to the resource. */
  val definitionCount: Int
    get() = distinctDefinitions.size

  /** The triggers bound to those definitions, each counted once. */
  val triggerCount: Int
    get() = distinctDefinitions.sumOf { it.triggers }
}

/**
 * Looks up who reaches shared resources. A role is a name inside a definition's metadata and its
 * binding is a row by pipeline name; nothing links either to a resource in the database
 * (06-data-model), so this is how the link is found.
 */
interface ResourceDeclarationStore {
  /** For each of [names], who declares it; a name nobody declares has no definitions. */
  fun declaredBy(names: Collection<String>): Map<String, ResourceDeclarations>
}
