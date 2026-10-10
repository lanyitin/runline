package dev.lawlan.runline.engine.resource

import kotlinx.serialization.Serializable

/** Body of a request to bind a role to the resource named. */
@Serializable data class BindRoleRequest(val resource: String)

/** The answer to a binding that is refused as invalid: [problem] is the category of the reason. */
@Serializable
data class InvalidBindingResponse(val error: String, val message: String, val problem: String)

@Serializable data class RoleBindingDoc(val resource: String, val setBy: String, val setAt: String)

/** A resource a role could be bound to; a developer cannot list resources, so this is how. */
@Serializable data class CandidateDoc(val name: String, val enabled: Boolean)

@Serializable
data class RoleStatusDoc(
    val role: String,
    /** The type the pipeline expects; null when it declared the name only. */
    val type: String?,
    val purpose: String?,
    /** The explicit binding; null when the role gets the resource of its own name. */
    val binding: RoleBindingDoc?,
    /** The resource the role gets: the bound one, else the one of its own name. */
    val resource: String,
    /** Whether the role has no binding, so that it gets the resource of its own name. */
    val isDefault: Boolean,
    val exists: Boolean,
    /** Null when the resource does not exist. */
    val enabled: Boolean?,
    /** The type of the resource; null when it does not exist. */
    val resourceType: String?,
    /** Whether the resource has the type the role expects; null when it does not exist. */
    val typeMatches: Boolean?,
    /** `unbound`, `unknown`, `disabled` or `type_mismatch`, as a run would find it; or null. */
    val problem: String?,
    val candidates: List<CandidateDoc>,
)

@Serializable
data class StaleBindingDoc(
    val role: String,
    val resource: String,
    val setBy: String,
    val setAt: String,
)

@Serializable
data class ResourceBindingsResponse(
    val contentHash: String,
    val uploader: String,
    val pipeline: String,
    val roles: List<RoleStatusDoc>,
    /** The uploaders of this content the caller can see (for an administrator to choose from). */
    val uploaders: List<String>,
    /** Bindings of roles the version no longer declares; removed by removing them one by one. */
    val staleBindings: List<StaleBindingDoc>,
)

fun RoleBinding.toDoc() = RoleBindingDoc(resource, setBy, setAt.toString())

fun BindingsView.toResponse() =
    ResourceBindingsResponse(
        contentHash,
        uploader,
        pipeline,
        roles.map { it.toDoc() },
        uploaders,
        stale.map { StaleBindingDoc(it.role, it.resource, it.setBy, it.setAt.toString()) },
    )

private fun RoleStatus.toDoc() =
    RoleStatusDoc(
        role,
        type,
        purpose,
        binding?.toDoc(),
        resource,
        isDefault,
        definition != null,
        definition?.enabled,
        definition?.type?.wireName,
        typeMatches,
        problem?.wire,
        candidates.map { CandidateDoc(it.name, it.enabled) },
    )
