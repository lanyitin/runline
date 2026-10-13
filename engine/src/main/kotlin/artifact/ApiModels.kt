package dev.lawlan.runline.engine.artifact

import dev.lawlan.runline.analyzer.SafetyReport
import dev.lawlan.runline.engine.resource.WarningDoc
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/** Body of every error response of the upload and query API. */
@Serializable data class ErrorResponse(val error: String, val message: String)

@Serializable
data class PipelineResponse(
    val className: String,
    val name: String,
    val metadata: MetadataDoc,
    val verdict: String,
    val reasons: List<ReasonDoc>,
    val allowListVersion: String,
    val allowUnsafeExecution: Boolean,
    /** What does not change the verdict but needs attention, such as a resource not defined. */
    val warnings: List<WarningDoc> = emptyList(),
)

/**
 * Refusal to pick a version: an administrator can see several versions of the content and did not
 * name the [uploaders] one (ADR-020). Nothing was changed.
 */
@Serializable
data class AmbiguousVersionResponse(
    val error: String,
    val message: String,
    val uploaders: List<String>,
)

/**
 * A version (the content with one uploader's judgement of it) and the pipelines found in it.
 * [uploader] names the version for the administrator's `uploader` parameter; [uploadedBy] says the
 * same and stays for existing callers.
 */
@Serializable
data class ArtifactResponse(
    val contentHash: String,
    val uploader: String,
    val sizeBytes: Long,
    val uploadedBy: String,
    val uploadedAt: String,
    val pipelines: List<PipelineResponse>,
    /** What the verdicts do not cover (ADR-002, ADR-011). */
    val limitations: String = SafetyReport.LIMITATIONS,
)

/** One definition in a listing, together with the version it belongs to. */
@Serializable
data class DefinitionEntry(
    val contentHash: String,
    val uploader: String,
    val uploadedBy: String,
    val uploadedAt: String,
    val className: String,
    val name: String,
    val metadata: MetadataDoc,
    val verdict: String,
    val reasons: List<ReasonDoc>,
    val allowListVersion: String,
    val allowUnsafeExecution: Boolean,
    val warnings: List<WarningDoc> = emptyList(),
)

@Serializable
data class DefinitionListResponse(
    val definitions: List<DefinitionEntry>,
    val limitations: String = SafetyReport.LIMITATIONS,
)

private fun DefinitionRecord.toResponse(warnings: List<WarningDoc>) =
    PipelineResponse(
        className,
        name,
        metadata,
        verdict.name,
        reasons,
        allowListVersion,
        allowUnsafeExecution,
        warnings,
    )

/** [warnings] gives the warnings of each definition. */
fun ArtifactRecord.toResponse(warnings: (DefinitionRecord) -> List<WarningDoc>) =
    ArtifactResponse(
        contentHash,
        uploadedBy,
        sizeBytes,
        uploadedBy,
        uploadedAt.toString(),
        definitions.map { it.toResponse(warnings(it)) },
    )

fun List<ArtifactRecord>.toDefinitionList(warnings: (DefinitionRecord) -> List<WarningDoc>) =
    DefinitionListResponse(
        flatMap { artifact ->
          artifact.definitions.map {
            DefinitionEntry(
                artifact.contentHash,
                artifact.uploadedBy,
                artifact.uploadedBy,
                artifact.uploadedAt.toString(),
                it.className,
                it.name,
                it.metadata,
                it.verdict.name,
                it.reasons,
                it.allowListVersion,
                it.allowUnsafeExecution,
                warnings(it),
            )
          }
        }
    )

/** The runs in a [DeletionImpactResponse]; the range is left out when there are no runs. */
@Serializable
data class RunImpactResponse(
    val total: Long,
    val byState: Map<String, Long>,
    val active: Long,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val earliestAt: String? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val latestAt: String? = null,
)

@Serializable data class TriggerReferenceDoc(val name: String, val kind: String)

/** What deleting a version would take along or be stopped by (05-ipc, ADR-047). */
@Serializable
data class DeletionImpactResponse(
    val runs: RunImpactResponse,
    val logEntries: Long,
    val triggers: List<TriggerReferenceDoc>,
    val contentRemoved: Boolean,
    val deletable: Boolean,
)

fun DeletionImpact.toResponse() =
    DeletionImpactResponse(
        RunImpactResponse(
            runs.total,
            runs.byState,
            runs.active,
            runs.earliestAt?.toString(),
            runs.latestAt?.toString(),
        ),
        logEntries,
        triggers.map { TriggerReferenceDoc(it.name, it.kind) },
        contentRemoved,
        deletable,
    )

/** 409 `in_use`: the triggers that still point at the version. */
@Serializable
data class InUseResponse(
    val error: String,
    val message: String,
    val triggers: List<TriggerReferenceDoc>,
)

/** 409 `runs_active`: how many runs have not ended. */
@Serializable
data class RunsActiveResponse(val error: String, val message: String, val active: Long)

/** 409 `runs_exist` and `impact_changed`: how many runs the version has now. */
@Serializable data class RunsTotalResponse(val error: String, val message: String, val total: Long)
