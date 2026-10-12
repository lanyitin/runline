package dev.lawlan.runline.core

/**
 * Declares a class as a pipeline entry point together with its metadata.
 *
 * The metadata lives in the compiled class as annotation values, so it can be read without running
 * any pipeline logic. Triggers are deliberately not part of the metadata; they are bound by
 * administrators in the Engine.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class PipelineDefinition(
    val name: String,
    val parameters: Array<Param> = [],
    /** File scopes the pipeline may use. A scope that is not listed is unavailable. */
    val files: Array<FileAccess> = [],
    /** Allowed network destinations as `host` entries. Not provided means unrestricted. */
    val network: AccessLimit = AccessLimit(unrestricted = true),
    /** Allowed external executables, by command name. Not provided means unrestricted. */
    val processes: AccessLimit = AccessLimit(unrestricted = true),
    /**
     * Names of shared resources the pipeline may use. Does not influence safe/unsafe
     * classification.
     */
    val resources: Array<String> = [],
    /**
     * Shared resources the pipeline uses together with the type it expects each to have (ADR-019).
     * A name listed here is also a declared resource name; naming a resource in both places is
     * allowed.
     */
    val typedResources: Array<TypedResource> = [],
    /**
     * Read-only views of the data the pipeline accumulates, shown by the Console (ADR-033). Purely
     * declarative: nothing here is run to read it, and it does not influence safe/unsafe
     * classification, resource roles or bindings.
     */
    val views: Array<View> = [],
)

/**
 * A resource role: the name the pipeline uses to reach a resource (ADR-025), with the type the
 * pipeline expects it to be (see [ResourceTypes]) and, optionally, what the resource is for. Which
 * resource is behind the role is decided outside the pipeline; with no binding it is the resource
 * of the same name. The [purpose] is a short text for people, never read by the Engine to decide
 * anything; empty means none.
 */
@Target()
@Retention(AnnotationRetention.RUNTIME)
annotation class TypedResource(val name: String, val type: String, val purpose: String = "")

@Target()
@Retention(AnnotationRetention.RUNTIME)
annotation class Param(
    val name: String,
    val required: Boolean = true,
    /** Value used when the parameter is not required and not supplied. */
    val default: String = "",
)

@Target()
@Retention(AnnotationRetention.RUNTIME)
annotation class FileAccess(
    val scope: FileScope,
    val mode: FileMode = FileMode.READ_ONLY,
)

/** Either an explicit allow list or unrestricted. */
@Target()
@Retention(AnnotationRetention.RUNTIME)
annotation class AccessLimit(
    val unrestricted: Boolean = false,
    val allow: Array<String> = [],
)

/** The kind of value a [ViewParam] takes. */
enum class ViewParamType {
  TEXT,
  INTEGER,
  /** One of the fixed [ViewParam.options]. */
  CHOICE,
}

/** The kind of value a [ViewColumn] shows. */
enum class ViewColumnType {
  TEXT,
  INTEGER,
  TIME,
  /** One of a few values, each with a [Tone] the Console shows in colour and in words. */
  STATUS,
}

/** How a status value is to be read by a person. */
enum class Tone {
  POSITIVE,
  NEUTRAL,
  NEGATIVE,
}

/**
 * A named, read-only query over a database resource role of the pipeline (ADR-033). [id] names the
 * view within the pipeline; [resource] is a role declared in [PipelineDefinition.typedResources]
 * with type [ResourceTypes.JDBC_POOL]. The [query] is text for that database and refers to the
 * declared parameters by name, as `:name` (ADR-034); parameter values are only ever passed as
 * values, never joined into it.
 */
@Target()
@Retention(AnnotationRetention.RUNTIME)
annotation class View(
    val id: String,
    val title: String,
    val description: String = "",
    val resource: String,
    val query: String,
    val parameters: Array<ViewParam> = [],
    val columns: Array<ViewColumn>,
    /** The default order, by column names; empty means the order of the query. */
    val sort: Array<ViewSort> = [],
)

@Target()
@Retention(AnnotationRetention.RUNTIME)
annotation class ViewParam(
    val name: String,
    val type: ViewParamType = ViewParamType.TEXT,
    val required: Boolean = true,
    /** The allowed values of a [ViewParamType.CHOICE] parameter. */
    val options: Array<String> = [],
)

@Target()
@Retention(AnnotationRetention.RUNTIME)
annotation class ViewColumn(
    val name: String,
    val label: String,
    val type: ViewColumnType = ViewColumnType.TEXT,
    /**
     * The tone of each value of a [ViewColumnType.STATUS] column; a value not listed is neutral.
     */
    val tones: Array<StatusTone> = [],
    /** At most one: opens another view of the same pipeline with a value of the row. */
    val drill: Array<Drill> = [],
)

@Target()
@Retention(AnnotationRetention.RUNTIME)
annotation class StatusTone(val value: String, val tone: Tone)

/**
 * Opens the view [view] with the value of column [from] of the row as its parameter [parameter]. An
 * empty [from] means the column that declares the drill.
 */
@Target()
@Retention(AnnotationRetention.RUNTIME)
annotation class Drill(val view: String, val parameter: String, val from: String = "")

@Target()
@Retention(AnnotationRetention.RUNTIME)
annotation class ViewSort(val column: String, val descending: Boolean = false)
