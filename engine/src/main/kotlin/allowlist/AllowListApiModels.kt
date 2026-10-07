package dev.lawlan.runline.engine.allowlist

import dev.lawlan.runline.analyzer.SafetyReport
import kotlinx.serialization.Serializable

/** Body of `POST /api/v1/allowlist/entries`. [kind] is `package` or `class`. */
@Serializable
data class AddEntryRequest(
    val kind: String,
    val name: String,
    /** "This package only"; package entries only. */
    val exactOnly: Boolean = false,
)

/** Body of `PATCH /api/v1/allowlist/entries/{kind}/{name}`; at least one field. */
@Serializable
data class ModifyEntryRequest(val name: String? = null, val exactOnly: Boolean? = null)

/**
 * One entry. [kind] says whether it is a `package` or a `class`; [exactOnly] is only meaningful for
 * a package and is null for a class.
 */
@Serializable
data class EntryResponse(
    val kind: String,
    val name: String,
    val exactOnly: Boolean?,
    val createdBy: String,
    val createdAt: String,
    val updatedBy: String,
    val updatedAt: String,
)

/** The allow list in force: [version] is the number of the current version, as text. */
@Serializable
data class AllowListResponse(
    val version: String,
    val changedBy: String,
    val changedAt: String,
    val entries: List<EntryResponse>,
    /** What a verdict made with this list does not cover (ADR-002, ADR-013). */
    val limitations: String = SafetyReport.LIMITATIONS,
)

@Serializable
data class VersionResponse(
    val version: String,
    val changedBy: String,
    val changedAt: String,
    val action: String,
    val detail: String,
    val rejudgedDefinitions: Int,
    val becameUnsafe: Int,
    val becameSafe: Int,
)

@Serializable data class VersionListResponse(val versions: List<VersionResponse>)

@Serializable
data class VerdictChangeResponse(
    val contentHash: String,
    val uploader: String,
    val pipeline: String,
    val className: String,
    val from: String,
    val to: String,
    val allowUnsafeExecution: Boolean,
    val unsafeExecutionRevoked: Boolean,
)

@Serializable
data class ImpactResponse(
    val examinedArtifacts: Int,
    val examinedDefinitions: Int,
    val becameUnsafe: Int,
    val becameSafe: Int,
    val unreadable: Int,
    val changes: List<VerdictChangeResponse>,
)

/**
 * What a change did, or in a preview would do. [version] is the version in force after the
 * operation: the new one after a change of the entries, otherwise the current one. [entry] is the
 * entry as it is now (null for a removal and for a preview).
 */
@Serializable
data class ChangeResponse(
    val preview: Boolean,
    val version: String,
    val entry: EntryResponse?,
    val impact: ImpactResponse,
    val redundantEntries: List<EntryResponse>,
    val limitations: String = SafetyReport.LIMITATIONS,
)

@Serializable
data class EntryRefusal(
    val error: String,
    val message: String,
    val problem: String? = null,
    val existing: EntryResponse? = null,
    val coveredBy: EntryResponse? = null,
)

val EntryKind.wireName: String
  get() = name.lowercase()

fun StoredEntry.toResponse() =
    EntryResponse(
        kind.wireName,
        name,
        if (kind == EntryKind.PACKAGE) exactOnly else null,
        createdBy,
        createdAt.toString(),
        updatedBy,
        updatedAt.toString(),
    )

fun AllowListVersion.toResponse() =
    VersionResponse(
        version.toString(),
        changedBy,
        changedAt.toString(),
        action.name,
        detail,
        rejudgedDefinitions,
        becameUnsafe,
        becameSafe,
    )

fun AllowListSnapshot.toResponse() =
    AllowListResponse(
        number.toString(),
        version.changedBy,
        version.changedAt.toString(),
        entries.map { it.toResponse() },
    )

fun Impact.toResponse() =
    ImpactResponse(
        examinedArtifacts,
        examinedDefinitions,
        becameUnsafe,
        becameSafe,
        unreadable,
        changes.map {
          VerdictChangeResponse(
              it.contentHash,
              it.uploader,
              it.pipeline,
              it.className,
              it.from.name,
              it.to.name,
              it.allowUnsafeExecution,
              it.unsafeExecutionRevoked,
          )
        },
    )
