package dev.lawlan.runline.engine.artifact

import dev.lawlan.runline.analyzer.SafetyReport
import dev.lawlan.runline.engine.resource.WarningDoc
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

/** The artifact (a version identified by its content hash) with the pipelines found in it. */
@Serializable
data class ArtifactResponse(
    val contentHash: String,
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
