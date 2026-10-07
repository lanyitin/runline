package dev.lawlan.runline.engine.artifact

import dev.lawlan.runline.analyzer.Verdict
import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.Serializable

/**
 * Pipeline metadata as stored and as returned by the API. Plain strings and lists, independent of
 * the pipeline authoring types in core.
 */
@Serializable
data class MetadataDoc(
    val parameters: List<ParameterDoc>,
    val files: List<FileAccessDoc>,
    val network: AccessLimitDoc,
    val processes: AccessLimitDoc,
    /** Every declared resource name, typed or not. */
    val resources: List<String>,
    /** The type the pipeline expects for some of [resources], by name (ADR-019). */
    val resourceTypes: Map<String, String> = emptyMap(),
)

@Serializable
data class ParameterDoc(val name: String, val required: Boolean, val default: String? = null)

@Serializable data class FileAccessDoc(val scope: String, val mode: String)

@Serializable
data class AccessLimitDoc(val unrestricted: Boolean, val allow: List<String> = listOf())

@Serializable
enum class ReasonKind {
  UNRESTRICTED_ACCESS,
  NOT_ALLOW_LISTED,
  JVM_EXIT,
  IO_SENSITIVE_MEMBER,
  UNREADABLE_CLASS,
  LIMIT_EXCEEDED,
}

/**
 * One reason a pipeline is unsafe, with the dependency path that introduced it where there is one.
 */
@Serializable
data class ReasonDoc(
    val kind: ReasonKind,
    val category: String? = null,
    val className: String? = null,
    val member: String? = null,
    val path: List<String> = listOf(),
    val detail: String? = null,
)

/** A pipeline found in an artifact, with its verdict, ready to be stored. */
data class NewDefinition(
    val className: String,
    val name: String,
    val metadata: MetadataDoc,
    val verdict: Verdict,
    val reasons: List<ReasonDoc>,
    val allowListVersion: String,
)

/** An uploaded jar and everything discovered in it. [content] is read once, when stored. */
data class NewArtifact(
    val contentHash: String,
    val content: Path,
    val sizeBytes: Long,
    val uploadedBy: String,
    val uploadedAt: Instant,
    val definitions: List<NewDefinition>,
    /**
     * The allow list version the definitions were judged under. When given, the artifact is stored
     * only if that is still the current version at the moment of storing, so a verdict made under a
     * list that has just been replaced is never stored (WI-10). Null skips the check.
     */
    val requiredAllowListVersion: String? = null,
)

data class DefinitionRecord(
    val className: String,
    val name: String,
    val metadata: MetadataDoc,
    val verdict: Verdict,
    val reasons: List<ReasonDoc>,
    val allowListVersion: String,
    /** Per-definition unsafe execution setting (ADR-006); a new definition never inherits it. */
    val allowUnsafeExecution: Boolean,
    val unsafeSettingSetBy: String?,
)

data class ArtifactRecord(
    val contentHash: String,
    val sizeBytes: Long,
    val uploadedBy: String,
    val uploadedAt: Instant,
    val definitions: List<DefinitionRecord>,
)
