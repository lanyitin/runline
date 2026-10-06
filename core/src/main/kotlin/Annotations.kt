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
)

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
