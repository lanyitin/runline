package dev.lawlan.runline.analyzer

/** A declared parameter. [default] is null for a required parameter. */
data class ParameterMetadata(val name: String, val required: Boolean, val default: String?)

/** A declared file scope and its mode, as the names of the corresponding core enum constants. */
data class FileAccessMetadata(val scope: String, val mode: String)

/**
 * A declared network or process limit. A limit that is not written is unrestricted; a written one
 * is an allow list unless it says otherwise.
 */
data class AccessLimitMetadata(val unrestricted: Boolean, val allow: List<String> = listOf())

/**
 * Everything a pipeline declares, read from its class file without loading or running it. Plain
 * strings and lists, so the module needs no type of core.
 */
data class PipelineMetadata(
    val name: String,
    val parameters: List<ParameterMetadata>,
    val files: List<FileAccessMetadata>,
    val network: AccessLimitMetadata,
    val processes: AccessLimitMetadata,
    /** Every declared resource name, typed or not. */
    val resources: List<String>,
    /** The type expected for some of [resources], as the type name written; others need none. */
    val resourceTypes: Map<String, String> = mapOf(),
)

/** A class annotated as a pipeline whose declaration could not be read. */
data class MetadataProblem(val className: String, val detail: String)
