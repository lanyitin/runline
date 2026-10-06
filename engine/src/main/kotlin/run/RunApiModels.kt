package dev.lawlan.runline.engine.run

import dev.lawlan.runline.engine.artifact.StoredDefinition
import kotlinx.serialization.Serializable

@Serializable
data class CreateRunRequest(
    /** The version (content hash) the pipeline is in. */
    val contentHash: String,
    /** The pipeline's name within that version. */
    val pipeline: String,
    val parameters: Map<String, String> = emptyMap(),
)

@Serializable data class SourceDoc(val kind: String, val name: String)

@Serializable
data class FailureDoc(val type: String, val message: String? = null, val trace: String)

@Serializable data class UnsafeExecutionDoc(val setBy: String, val setAt: String)

@Serializable
data class RunResponse(
    val runId: String,
    val state: String,
    val contentHash: String,
    val pipeline: String,
    val className: String,
    val source: SourceDoc,
    val parameters: Map<String, String>,
    val createdAt: String,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val failure: FailureDoc? = null,
    val unsafeExecution: UnsafeExecutionDoc? = null,
)

@Serializable data class RunListResponse(val runs: List<RunResponse>)

@Serializable data class ParameterProblemDoc(val name: String, val problem: String)

/** Refusal of a run's parameters: says which parameter is wrong and how. */
@Serializable
data class ParameterErrorResponse(
    val error: String,
    val message: String,
    val problems: List<ParameterProblemDoc>,
)

@Serializable
data class CancelResponse(val runId: String, val state: String, val cancellation: String)

@Serializable
data class LogEntryDoc(val seq: Long, val at: String, val stream: String, val line: String)

/** [last] is the sequence number to continue after: the last entry returned, else the one asked. */
@Serializable data class LogResponse(val entries: List<LogEntryDoc>, val last: Long)

@Serializable data class UnsafeExecutionRequest(val allow: Boolean)

@Serializable
data class UnsafeExecutionResponse(
    val contentHash: String,
    val pipeline: String,
    val allow: Boolean,
    val setBy: String?,
    val setAt: String?,
)

fun RunRecord.toResponse() =
    RunResponse(
        runId = id.toString(),
        state = state.name,
        contentHash = contentHash,
        pipeline = pipelineName,
        className = className,
        source = SourceDoc(source.kind, source.name),
        parameters = parameters,
        createdAt = createdAt.toString(),
        startedAt = startedAt?.toString(),
        finishedAt = finishedAt?.toString(),
        failure = failure?.let { FailureDoc(it.type, it.message, it.trace) },
        unsafeExecution =
            unsafeExecution?.let { UnsafeExecutionDoc(it.setBy, it.setAt.toString()) },
    )

fun LogEntry.toDoc() = LogEntryDoc(seq, at.toString(), stream.name, line)

fun StoredDefinition.toUnsafeResponse() =
    UnsafeExecutionResponse(
        contentHash,
        name,
        allowUnsafeExecution,
        unsafeSettingSetBy,
        unsafeSettingSetAt?.toString(),
    )
