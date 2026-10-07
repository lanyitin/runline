package dev.lawlan.runline.engine.resource

/** A pipeline definition that declares a shared resource, and what is bound to it. */
data class DeclaringDefinition(
    val contentHash: String,
    val pipeline: String,
    /** The type the definition expects of the resource; null when it declares the name only. */
    val declaredType: String?,
    /** How many triggers are bound to this definition, and so go through its declaration. */
    val triggers: Int,
)

/**
 * Who declares one resource: the definitions, and with them the triggers that would be affected.
 */
data class ResourceDeclarations(val definitions: List<DeclaringDefinition>) {
  val triggerCount: Int
    get() = definitions.sumOf { it.triggers }
}

/**
 * Looks up who declares shared resources. A declaration is a name inside a definition's metadata;
 * nothing links the two in the database (06-data-model), so this is how the link is found.
 */
interface ResourceDeclarationStore {
  /** For each of [names], who declares it; a name nobody declares has no definitions. */
  fun declaredBy(names: Collection<String>): Map<String, ResourceDeclarations>
}
