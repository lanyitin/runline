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
    /** What some of [resources] are for (ADR-025); only the roles that say it, as written. */
    val resourcePurposes: Map<String, String> = mapOf(),
    /** The views declared (ADR-033); none for a pipeline that declares none. */
    val views: List<ViewMetadata> = listOf(),
)

/**
 * A declared view, as written. Types and tones are the names of the corresponding core enum
 * constants; whether they are known is for the reader of this metadata to judge.
 */
data class ViewMetadata(
    val id: String,
    val title: String,
    val description: String,
    val resource: String,
    val query: String,
    val parameters: List<ViewParameterMetadata>,
    val columns: List<ViewColumnMetadata>,
    val sort: List<ViewSortMetadata>,
)

data class ViewParameterMetadata(
    val name: String,
    val type: String,
    val required: Boolean,
    val options: List<String>,
)

data class ViewColumnMetadata(
    val name: String,
    val label: String,
    val type: String,
    val tones: List<StatusToneMetadata>,
    val drill: DrillMetadata?,
)

data class StatusToneMetadata(val value: String, val tone: String)

/** [from] is always a column name: a drill that names none takes the column that declares it. */
data class DrillMetadata(val view: String, val parameter: String, val from: String)

data class ViewSortMetadata(val column: String, val descending: Boolean)

/** A class annotated as a pipeline whose declaration could not be read. */
data class MetadataProblem(val className: String, val detail: String)
